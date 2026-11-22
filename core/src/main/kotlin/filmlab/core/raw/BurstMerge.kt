package filmlab.core.raw

import filmlab.core.util.Parallel
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * RAW 連写の位置合わせ・合成（HDR+ と同系統の簡易版）。
 *  1. 各フレームを 2x2 ビニングした輝度画像のピラミッドを作る
 *  2. 全体のずれを粗い段で探索 → タイル（RAW 32px）ごとに細かい段で補正
 *     （ずれは RAW 上で偶数画素にしてベイヤー配列の位相を保つ）
 *  3. 基準フレームとの差がノイズに比べて大きい画素（動いた被写体など）は重みを下げて平均
 * 出力は基準フレームと同じ形式の RAW16（黒レベル込み）。
 */
object BurstMerge {
    private const val TILE = 16 // ビニング画像でのタイル（= RAW 32px）

    class Result(val raw: ShortArray, val reference: Int, val note: String)

    fun merge(frames: List<ShortArray>, w: Int, h: Int, black: Float, white: Float): Result {
        require(frames.isNotEmpty())
        if (frames.size == 1) return Result(frames[0], 0, "1 枚（合成なし）")
        val bw = w / 2
        val bh = h / 2
        val bins = frames.map { bin(it, w, h, black) }
        // 最もシャープなフレームを基準にする（手ブレの少ない 1 枚）
        val ref = bins.indices.maxBy { sharpness(bins[it], bw, bh) }
        val refPyr = pyramid(bins[ref], bw, bh)
        val tx = (bw + TILE - 1) / TILE
        val ty = (bh + TILE - 1) / TILE
        val offsets = HashMap<Int, IntArray>() // frame -> (dx,dy) per tile（ビニング画素）
        var maxShift = 0
        for (f in frames.indices) {
            if (f == ref) continue
            val pyr = pyramid(bins[f], bw, bh)
            val g = globalShift(refPyr, pyr)
            maxShift = max(maxShift, max(abs(g[0]), abs(g[1])))
            val off = IntArray(tx * ty * 2)
            Parallel.forChunks(ty, 1) { s, e ->
                for (j in s until e) for (i in 0 until tx) {
                    val best = refineTile(refPyr[0], pyr[0], bw, bh, i * TILE, j * TILE, g[0], g[1], 2)
                    off[(j * tx + i) * 2] = best[0]
                    off[(j * tx + i) * 2 + 1] = best[1]
                }
            }
            offsets[f] = off
        }
        // ノイズの大きさを明るさごとに推定（基準と位置合わせ後の差の中央値。動きの影響を受けにくい）
        val sigma = noiseByLevel(bins, ref, offsets, bw, bh, tx, white - black)

        val refRaw = frames[ref]
        val out = ShortArray(w * h)
        val levels = sigma.size
        val range = (white - black) * 4f // ビニング値（4 画素の和）のレンジ
        Parallel.forChunks(bh, 8) { s, e ->
            val acc = FloatArray(4)
            for (by in s until e) for (bx in 0 until bw) {
                val rb = bins[ref][by * bw + bx]
                val lvl = min(levels - 1, max(0, (rb / range * levels).toInt()))
                val sg = sigma[lvl]
                val tIdx = (min(ty - 1, by / TILE) * tx + min(tx - 1, bx / TILE)) * 2
                val x0 = bx * 2
                val y0 = by * 2
                acc[0] = 0f; acc[1] = 0f; acc[2] = 0f; acc[3] = 0f
                var wsum = 1f
                for (k in 0..3) acc[k] = refRaw[(y0 + (k shr 1)) * w + x0 + (k and 1)].toInt().and(0xFFFF).toFloat()
                for (f in frames.indices) {
                    if (f == ref) continue
                    val off = offsets[f]!!
                    val dx = off[tIdx]
                    val dy = off[tIdx + 1]
                    val sx = bx + dx
                    val sy = by + dy
                    if (sx < 0 || sy < 0 || sx >= bw || sy >= bh) continue
                    val d = bins[f][sy * bw + sx] - rb
                    val z = d / (3f * sg)
                    val wt = exp(-(z * z))
                    if (wt < 0.02f) continue
                    val fr = frames[f]
                    val fx = sx * 2
                    val fy = sy * 2
                    for (k in 0..3) acc[k] += wt * (fr[(fy + (k shr 1)) * w + fx + (k and 1)].toInt() and 0xFFFF)
                    wsum += wt
                }
                for (k in 0..3) {
                    val v = acc[k] / wsum + 0.5f
                    out[(y0 + (k shr 1)) * w + x0 + (k and 1)] = min(white, max(0f, v)).toInt().toShort()
                }
            }
        }
        // 奇数サイズの端の行・列は基準フレームのまま
        if (w % 2 == 1) for (y in 0 until h) out[y * w + w - 1] = refRaw[y * w + w - 1]
        if (h % 2 == 1) System.arraycopy(refRaw, (h - 1) * w, out, (h - 1) * w, w)
        return Result(out, ref, "${frames.size} 枚合成（基準 #${ref + 1}、最大ずれ ${maxShift * 2}px）")
    }

    /** 2x2 の和（黒レベルを引いた値） */
    private fun bin(f: ShortArray, w: Int, h: Int, black: Float): FloatArray {
        val bw = w / 2
        val bh = h / 2
        val o = FloatArray(bw * bh)
        Parallel.forChunks(bh, 16) { s, e ->
            for (y in s until e) for (x in 0 until bw) {
                val i = y * 2 * w + x * 2
                o[y * bw + x] = (f[i].toInt() and 0xFFFF) + (f[i + 1].toInt() and 0xFFFF) +
                    (f[i + w].toInt() and 0xFFFF) + (f[i + w + 1].toInt() and 0xFFFF) - 4f * black
            }
        }
        return o
    }

    private class Level(val d: FloatArray, val w: Int, val h: Int)

    private fun pyramid(b: FloatArray, w: Int, h: Int): List<Level> {
        val list = ArrayList<Level>()
        var cur = Level(b, w, h)
        list.add(cur)
        while (cur.w > 128 && cur.h > 128 && list.size < 5) {
            val nw = cur.w / 2
            val nh = cur.h / 2
            val d = FloatArray(nw * nh)
            val c = cur
            for (y in 0 until nh) for (x in 0 until nw) {
                val i = y * 2 * c.w + x * 2
                d[y * nw + x] = 0.25f * (c.d[i] + c.d[i + 1] + c.d[i + c.w] + c.d[i + c.w + 1])
            }
            cur = Level(d, nw, nh)
            list.add(cur)
        }
        return list
    }

    private fun sharpness(b: FloatArray, w: Int, h: Int): Double {
        var s = 0.0
        var y = 1
        while (y < h - 1) {
            var x = 1
            while (x < w - 1) {
                val i = y * w + x
                s += abs(b[i + 1] - b[i - 1]) + abs(b[i + w] - b[i - w])
                x += 3
            }
            y += 3
        }
        return s
    }

    /** 粗い段から全体のずれを探索（返り値はビニング画素単位） */
    private fun globalShift(a: List<Level>, b: List<Level>): IntArray {
        var dx = 0
        var dy = 0
        for (li in a.indices.reversed()) {
            dx *= 2; dy *= 2
            val la = a[li]
            val lb = b[li]
            val r = if (li == a.size - 1) 8 else 2
            var best = Double.MAX_VALUE
            var bx = dx
            var by = dy
            val m = 8 + r
            for (oy in -r..r) for (ox in -r..r) {
                var s = 0.0
                var y = m
                while (y < la.h - m) {
                    var x = m
                    while (x < la.w - m) {
                        val yy = y + dy + oy
                        val xx = x + dx + ox
                        if (yy in 0 until lb.h && xx in 0 until lb.w) {
                            s += abs(la.d[y * la.w + x] - lb.d[yy * lb.w + xx])
                        } else {
                            s += la.d[y * la.w + x].toDouble().let { if (it < 0) -it else it }
                        }
                        x += if (li == 0) 4 else 1
                    }
                    y += if (li == 0) 4 else 1
                }
                if (s < best) {
                    best = s; bx = dx + ox; by = dy + oy
                }
            }
            dx = bx; dy = by
        }
        return intArrayOf(dx, dy)
    }

    /** タイル単位の細かい探索（L1 距離）。画像外にかかる候補は評価しない */
    private fun refineTile(a: Level, b: Level, w: Int, h: Int, x0: Int, y0: Int, gx: Int, gy: Int, r: Int): IntArray {
        var best = Float.MAX_VALUE
        var bx = gx
        var by = gy
        val x1 = min(w, x0 + TILE)
        val y1 = min(h, y0 + TILE)
        for (oy in -r..r) for (ox in -r..r) {
            val dx = gx + ox
            val dy = gy + oy
            if (x0 + dx < 0 || y0 + dy < 0 || x1 + dx > w || y1 + dy > h) continue
            var s = 0f
            for (y in y0 until y1) {
                val ra = y * w
                val rb = (y + dy) * w + dx
                for (x in x0 until x1) s += abs(a.d[ra + x] - b.d[rb + x])
            }
            if (s < best) {
                best = s; bx = dx; by = dy
            }
        }
        return intArrayOf(bx, by)
    }

    private fun noiseByLevel(
        bins: List<FloatArray>, ref: Int, offsets: Map<Int, IntArray>, bw: Int, bh: Int, tx: Int, range: Float,
    ): FloatArray {
        val levels = 16
        val hist = Array(levels) { ArrayList<Float>() }
        val other = offsets.keys.first()
        val off = offsets[other]!!
        val rb = bins[ref]
        val ob = bins[other]
        val ty = (bh + TILE - 1) / TILE
        var y = 0
        while (y < bh) {
            var x = 0
            while (x < bw) {
                val t = (min(ty - 1, y / TILE) * tx + min(tx - 1, x / TILE)) * 2
                val sx = x + off[t]
                val sy = y + off[t + 1]
                if (sx in 0 until bw && sy in 0 until bh) {
                    val v = rb[y * bw + x]
                    val lvl = min(levels - 1, max(0, (v / (range * 4f) * levels).toInt()))
                    hist[lvl].add(abs(ob[sy * bw + sx] - v))
                }
                x += 3
            }
            y += 3
        }
        val sig = FloatArray(levels)
        var last = 0f
        for (l in 0 until levels) {
            val hl = hist[l]
            if (hl.size > 50) {
                hl.sort()
                // 差の中央値から 1 フレームあたりの σ を推定（2 フレームの差なので √2 で割る）
                last = max(1f, 1.4826f * hl[hl.size / 2] / 1.4142f)
            }
            sig[l] = if (last > 0f) last else range * 0.01f
        }
        // データが無かった暗部側は最初の有効値で埋める
        val first = sig.firstOrNull { it > 0f } ?: 1f
        for (l in 0 until levels) if (sig[l] <= 0f) sig[l] = first
        return sig
    }
}
