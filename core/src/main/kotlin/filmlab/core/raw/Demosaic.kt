package filmlab.core.raw

import filmlab.core.dng.CfaPattern
import filmlab.core.util.Parallel
import kotlin.math.abs

/**
 * ベイヤーのデモザイク。Menon (2007) "Demosaicing With Directional Filtering and a posteriori
 * Decision" (DDFAPD) を行単位（ストリップ）で処理できるように実装。
 * 縦横どちらの方向で補間するかを色差の勾配で判定するため、ジッパーノイズや偽色が出にくい。
 */
class Demosaic(private val cfa: FloatArray, private val w: Int, private val h: Int, private val pat: CfaPattern) {
    companion object {
        const val MARGIN = 6
    }

    private var cap = 0
    private lateinit var c: FloatArray
    private lateinit var gh: FloatArray
    private lateinit var gv: FloatArray
    private lateinit var ch: FloatArray
    private lateinit var cv: FloatArray
    private lateinit var dh: FloatArray
    private lateinit var dv: FloatArray
    private lateinit var g: FloatArray
    private lateinit var r: FloatArray
    private lateinit var b: FloatArray
    private lateinit var dir: ByteArray

    private fun ensure(n: Int) {
        if (n <= cap) return
        cap = n
        c = FloatArray(n); gh = FloatArray(n); gv = FloatArray(n); ch = FloatArray(n); cv = FloatArray(n)
        dh = FloatArray(n); dv = FloatArray(n); g = FloatArray(n); r = FloatArray(n); b = FloatArray(n)
        dir = ByteArray(n)
    }

    private fun mirror(i: Int, n: Int): Int {
        var v = i
        if (n == 1) return 0
        while (v < 0 || v >= n) {
            if (v < 0) v = -v
            if (v >= n) v = 2 * (n - 1) - v
        }
        return v
    }

    private fun color(x: Int, y: Int): Int = pat.colorAt(x and 1, y and 1)

    private fun rowHasRed(y: Int): Boolean {
        val p = y and 1
        return pat.colors[p * 2] == 0 || pat.colors[p * 2 + 1] == 0
    }

    /** 出力行 [y0, y1) の RGB を outR/outG/outB（(y1-y0)*w）に書き込む。 */
    fun process(y0: Int, y1: Int, outR: FloatArray, outG: FloatArray, outB: FloatArray) {
        val wa = y0 - MARGIN
        val wh = (y1 - y0) + 2 * MARGIN
        val n = wh * w
        ensure(n)
        val c = c; val gh = gh; val gv = gv; val ch = ch; val cv = cv
        val dh = dh; val dv = dv; val g = g; val r = r; val b = b; val dir = dir
        val w = w

        // 1. CFA 読み込み（画像外はミラー。ミラーは偶奇を保つので CFA 色も一致する）
        Parallel.forChunks(wh, 16) { s, e ->
            for (i in s until e) {
                val sy = mirror(wa + i, h)
                System.arraycopy(cfa, sy * w, c, i * w, w)
            }
        }

        // 2. 水平/垂直方向の G 推定
        Parallel.forChunks(wh, 16) { s, e ->
            for (i in s until e) {
                val y = wa + i
                val row = i * w
                val im1 = mirror(i - 1, wh) * w
                val ip1 = mirror(i + 1, wh) * w
                val im2 = mirror(i - 2, wh) * w
                val ip2 = mirror(i + 2, wh) * w
                for (x in 0 until w) {
                    val k = row + x
                    if (color(x, y) == 1) {
                        gh[k] = c[k]; gv[k] = c[k]
                        ch[k] = 0f; cv[k] = 0f
                    } else {
                        val xm1 = mirror(x - 1, w)
                        val xp1 = mirror(x + 1, w)
                        val xm2 = mirror(x - 2, w)
                        val xp2 = mirror(x + 2, w)
                        val ghv = 0.5f * (c[row + xm1] + c[row + xp1]) + 0.5f * c[k] - 0.25f * (c[row + xm2] + c[row + xp2])
                        val gvv = 0.5f * (c[im1 + x] + c[ip1 + x]) + 0.5f * c[k] - 0.25f * (c[im2 + x] + c[ip2 + x])
                        gh[k] = ghv; gv[k] = gvv
                        ch[k] = c[k] - ghv
                        cv[k] = c[k] - gvv
                    }
                }
            }
        }

        // 3. 色差の勾配
        Parallel.forChunks(wh, 16) { s, e ->
            for (i in s until e) {
                val row = i * w
                val ip2 = mirror(i + 2, wh) * w
                for (x in 0 until w) {
                    val k = row + x
                    dh[k] = abs(ch[k] - ch[row + mirror(x + 2, w)])
                    dv[k] = abs(cv[k] - cv[ip2 + x])
                }
            }
        }

        // 4. 方向判定（Menon の分類カーネル。画像外は 0 扱い）
        Parallel.forChunks(wh, 16) { s, e ->
            for (i in s until e) {
                val row = i * w
                for (x in 0 until w) {
                    val k = row + x
                    val sh = 3f * dh[k] + 3f * at(dh, x - 2, i, wh) +
                        at(dh, x, i + 2, wh) + at(dh, x - 2, i + 2, wh) +
                        at(dh, x - 1, i + 1, wh) + at(dh, x - 1, i - 1, wh) +
                        at(dh, x, i - 2, wh) + at(dh, x - 2, i - 2, wh)
                    val sv = 3f * dv[k] + 3f * at(dv, x, i - 2, wh) +
                        at(dv, x + 2, i, wh) + at(dv, x + 2, i - 2, wh) +
                        at(dv, x + 1, i - 1, wh) + at(dv, x - 1, i - 1, wh) +
                        at(dv, x - 2, i, wh) + at(dv, x - 2, i - 2, wh)
                    if (sv >= sh) {
                        g[k] = gh[k]; dir[k] = 1
                    } else {
                        g[k] = gv[k]; dir[k] = 0
                    }
                }
            }
        }

        // 5. R/B 初期値（自色サイトのみ）
        Parallel.forChunks(wh, 16) { s, e ->
            for (i in s until e) {
                val y = wa + i
                val row = i * w
                for (x in 0 until w) {
                    val k = row + x
                    when (color(x, y)) {
                        0 -> { r[k] = c[k]; b[k] = 0f }
                        2 -> { b[k] = c[k]; r[k] = 0f }
                        else -> { r[k] = 0f; b[k] = 0f }
                    }
                }
            }
        }

        // 6. G サイトの R/B（色差補間）
        Parallel.forChunks(wh, 16) { s, e ->
            for (i in s until e) {
                val y = wa + i
                val row = i * w
                val im1 = mirror(i - 1, wh) * w
                val ip1 = mirror(i + 1, wh) * w
                val redRow = rowHasRed(y)
                for (x in 0 until w) {
                    if (color(x, y) != 1) continue
                    val k = row + x
                    val xm1 = row + mirror(x - 1, w)
                    val xp1 = row + mirror(x + 1, w)
                    val gk = g[k]
                    val ghAvg = 0.5f * (g[xm1] + g[xp1])
                    val gvAvg = 0.5f * (g[im1 + x] + g[ip1 + x])
                    if (redRow) {
                        r[k] = gk + 0.5f * (r[xm1] + r[xp1]) - ghAvg
                        b[k] = gk + 0.5f * (b[im1 + x] + b[ip1 + x]) - gvAvg
                    } else {
                        b[k] = gk + 0.5f * (b[xm1] + b[xp1]) - ghAvg
                        r[k] = gk + 0.5f * (r[im1 + x] + r[ip1 + x]) - gvAvg
                    }
                }
            }
        }

        // 7. B サイトの R、R サイトの B（判定方向に沿って色差補間）
        Parallel.forChunks(wh, 16) { s, e ->
            for (i in s until e) {
                val y = wa + i
                val row = i * w
                val im1 = mirror(i - 1, wh) * w
                val ip1 = mirror(i + 1, wh) * w
                for (x in 0 until w) {
                    val col = color(x, y)
                    if (col == 1) continue
                    val k = row + x
                    val xm1 = row + mirror(x - 1, w)
                    val xp1 = row + mirror(x + 1, w)
                    val horiz = dir[k].toInt() == 1
                    if (col == 2) {
                        val d = if (horiz) 0.5f * ((r[xm1] - b[xm1]) + (r[xp1] - b[xp1]))
                        else 0.5f * ((r[im1 + x] - b[im1 + x]) + (r[ip1 + x] - b[ip1 + x]))
                        r[k] = b[k] + d
                    } else {
                        val d = if (horiz) 0.5f * ((b[xm1] - r[xm1]) + (b[xp1] - r[xp1]))
                        else 0.5f * ((b[im1 + x] - r[im1 + x]) + (b[ip1 + x] - r[ip1 + x]))
                        b[k] = r[k] + d
                    }
                }
            }
        }

        // 出力
        val outRows = y1 - y0
        val off = MARGIN * w
        System.arraycopy(r, off, outR, 0, outRows * w)
        System.arraycopy(g, off, outG, 0, outRows * w)
        System.arraycopy(b, off, outB, 0, outRows * w)
    }

    private fun at(a: FloatArray, x: Int, i: Int, wh: Int): Float {
        if (x < 0 || x >= w || i < 0 || i >= wh) return 0f
        return a[i * w + x]
    }
}
