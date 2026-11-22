package filmlab.core.dng

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * DNG OpcodeList（常に big-endian）。スマホの DNG ではレンズ補正がここに入っている:
 *  - OpcodeList2: GainMap（周辺減光・色シェーディング補正。ベイヤー各色ごとに 4 枚）
 *  - OpcodeList3: WarpRectilinear（歪曲・倍率色収差補正）、FixVignetteRadial
 * これを適用しないと四隅が暗く色かぶりし、歪曲も残る（多くの RAW 現像ソフトの弱点）。
 */
sealed class Opcode(val id: Int, val flags: Int) {
    val optional: Boolean get() = (flags and 1) != 0
    open val name: String get() = "Opcode$id"
}

class AreaSpec(
    val top: Int, val left: Int, val bottom: Int, val right: Int,
    val plane: Int, val planes: Int, val rowPitch: Int, val colPitch: Int,
) {
    override fun toString() = "area[t=$top l=$left b=$bottom r=$right plane=$plane/$planes pitch=$rowPitch,$colPitch]"
}

class GainMapOp(
    flags: Int,
    val area: AreaSpec,
    val pointsV: Int, val pointsH: Int,
    val spacingV: Double, val spacingH: Double,
    val originV: Double, val originH: Double,
    val mapPlanes: Int,
    val gains: FloatArray,
) : Opcode(9, flags) {
    override val name get() = "GainMap"

    fun gain(i: Int, j: Int, p: Int): Float = gains[(i * pointsH + j) * mapPlanes + p]

    /** マップ上の連続座標 f (0..n-1) を (下側インデックス, 小数部) に変換。dng_sdk と同じく端でクランプ。 */
    fun mapIndex(f: Double, n: Int, outIdx: IntArray, k: Int, outFrac: FloatArray) {
        if (f <= 0.0 || n <= 1) {
            outIdx[k] = 0; outFrac[k] = 0f; return
        }
        val last = n - 1
        if (f >= last) {
            outIdx[k] = last; outFrac[k] = 0f; return
        }
        val i = f.toInt()
        outIdx[k] = i
        outFrac[k] = (f - i).toFloat()
    }

    fun rowCoord(row: Int, imageH: Int): Double = ((row + 0.5) / imageH - originV) / spacingV
    fun colCoord(col: Int, imageW: Int): Double = ((col + 0.5) / imageW - originH) / spacingH
}

class WarpRectilinearOp(
    flags: Int,
    val planes: Int,
    /** plane ごとに kr0, kr1, kr2, kr3, kt0, kt1 */
    val coeffs: Array<DoubleArray>,
    val cx: Double, val cy: Double,
) : Opcode(1, flags) {
    override val name get() = "WarpRectilinear"

    fun isIdentity(): Boolean = coeffs.all { k ->
        kotlin.math.abs(k[0] - 1.0) < 1e-7 && kotlin.math.abs(k[1]) < 1e-9 && kotlin.math.abs(k[2]) < 1e-9 &&
            kotlin.math.abs(k[3]) < 1e-9 && kotlin.math.abs(k[4]) < 1e-9 && kotlin.math.abs(k[5]) < 1e-9
    }
}

class FixVignetteRadialOp(flags: Int, val k: DoubleArray, val cx: Double, val cy: Double) : Opcode(3, flags) {
    override val name get() = "FixVignetteRadial"
}

class FixBadPixelsConstantOp(flags: Int, val constant: Int, val bayerPhase: Int) : Opcode(4, flags) {
    override val name get() = "FixBadPixelsConstant"
}

class FixBadPixelsListOp(flags: Int, val bayerPhase: Int, val points: IntArray, val rects: IntArray) : Opcode(5, flags) {
    override val name get() = "FixBadPixelsList"
}

class MapTableOp(flags: Int, val area: AreaSpec, val table: IntArray) : Opcode(7, flags) {
    override val name get() = "MapTable"
}

class MapPolynomialOp(flags: Int, val area: AreaSpec, val coeffs: DoubleArray) : Opcode(8, flags) {
    override val name get() = "MapPolynomial"
}

/** DeltaPerRow(10), DeltaPerColumn(11), ScalePerRow(12), ScalePerColumn(13) */
class PerRowColOp(id: Int, flags: Int, val area: AreaSpec, val values: FloatArray) : Opcode(id, flags) {
    val perRow get() = id == 10 || id == 12
    val isScale get() = id == 12 || id == 13
    override val name get() = when (id) {
        10 -> "DeltaPerRow"; 11 -> "DeltaPerColumn"; 12 -> "ScalePerRow"; else -> "ScalePerColumn"
    }
}

class UnknownOp(id: Int, flags: Int) : Opcode(id, flags) {
    override val name get() = when (id) {
        2 -> "WarpFisheye"; 6 -> "TrimBounds"; 14 -> "WarpRectilinear2"; else -> "Opcode$id"
    }
}

object Opcodes {
    fun parse(bytes: ByteArray?): List<Opcode> {
        if (bytes == null || bytes.size < 4) return emptyList()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val count = b.getInt(0)
        val list = ArrayList<Opcode>()
        var p = 4
        for (n in 0 until count) {
            if (p + 16 > bytes.size) break
            val id = b.getInt(p)
            val flags = b.getInt(p + 8)
            val len = b.getInt(p + 12)
            val ps = p + 16
            if (len < 0 || ps + len > bytes.size) break
            val op = try {
                parseOne(id, flags, b, ps, len)
            } catch (e: Exception) {
                UnknownOp(id, flags)
            }
            list.add(op)
            p = ps + len
        }
        return list
    }

    private fun area(b: ByteBuffer, p: Int) = AreaSpec(
        b.getInt(p), b.getInt(p + 4), b.getInt(p + 8), b.getInt(p + 12),
        b.getInt(p + 16), b.getInt(p + 20), max(1, b.getInt(p + 24)), max(1, b.getInt(p + 28)),
    )

    private fun parseOne(id: Int, flags: Int, b: ByteBuffer, p: Int, len: Int): Opcode = when (id) {
        1 -> {
            val n = b.getInt(p)
            require(n in 1..4)
            val coeffs = Array(n) { i -> DoubleArray(6) { k -> b.getDouble(p + 4 + (i * 6 + k) * 8) } }
            val q = p + 4 + n * 48
            WarpRectilinearOp(flags, n, coeffs, b.getDouble(q), b.getDouble(q + 8))
        }
        3 -> FixVignetteRadialOp(flags, DoubleArray(5) { b.getDouble(p + it * 8) }, b.getDouble(p + 40), b.getDouble(p + 48))
        4 -> FixBadPixelsConstantOp(flags, b.getInt(p), b.getInt(p + 4))
        5 -> {
            val phase = b.getInt(p)
            val np = b.getInt(p + 4)
            val nr = b.getInt(p + 8)
            require(np >= 0 && nr >= 0 && 12 + np * 8 + nr * 16 <= len)
            val pts = IntArray(np * 2) { b.getInt(p + 12 + it * 4) }
            val rects = IntArray(nr * 4) { b.getInt(p + 12 + np * 8 + it * 4) }
            FixBadPixelsListOp(flags, phase, pts, rects)
        }
        7 -> {
            val a = area(b, p)
            val n = b.getInt(p + 32)
            require(n in 1..65536)
            MapTableOp(flags, a, IntArray(n) { b.getShort(p + 36 + it * 2).toInt() and 0xFFFF })
        }
        8 -> {
            val a = area(b, p)
            val deg = b.getInt(p + 32)
            require(deg in 0..8)
            MapPolynomialOp(flags, a, DoubleArray(deg + 1) { b.getDouble(p + 36 + it * 8) })
        }
        9 -> {
            val a = area(b, p)
            val pv = b.getInt(p + 32)
            val ph = b.getInt(p + 36)
            val sv = b.getDouble(p + 40)
            val sh = b.getDouble(p + 48)
            val ov = b.getDouble(p + 56)
            val oh = b.getDouble(p + 64)
            val mp = b.getInt(p + 72)
            require(pv >= 1 && ph >= 1 && mp >= 1 && 76 + pv * ph * mp * 4 <= len)
            val g = FloatArray(pv * ph * mp) { b.getFloat(p + 76 + it * 4) }
            GainMapOp(flags, a, pv, ph, sv, sh, ov, oh, mp, g)
        }
        10, 11, 12, 13 -> {
            val a = area(b, p)
            val n = b.getInt(p + 32)
            require(n in 0..100000)
            PerRowColOp(id, flags, a, FloatArray(n) { b.getFloat(p + 36 + it * 4) })
        }
        else -> UnknownOp(id, flags)
    }

    // ---------------- 適用 ----------------

    /**
     * 画素単位（位置依存だが近傍を参照しない）の opcode を plane 群に適用する。
     * planes: 各 plane の配列（width*rows）。rowOffset: この配列の先頭行が画像全体の何行目か。
     * imageW/imageH: opcode 座標系での画像全体の大きさ。colOffset: 配列の列 0 が画像座標の何列目か。
     */
    fun applyPointOps(
        ops: List<Opcode>, planes: Array<FloatArray>, width: Int, rows: Int,
        rowOffset: Int, colOffset: Int, imageW: Int, imageH: Int, clampGain: Boolean,
    ) {
        for (op in ops) {
            when (op) {
                is GainMapOp -> applyGainMap(op, planes, width, rows, rowOffset, colOffset, imageW, imageH, clampGain)
                is FixVignetteRadialOp -> applyVignette(op, planes, width, rows, rowOffset, colOffset, imageW, imageH)
                is MapPolynomialOp -> applyArea(op.area, planes, width, rows, rowOffset, colOffset) { v, _, _ ->
                    var x = 1.0
                    var s = 0.0
                    for (c in op.coeffs) {
                        s += c * x
                        x *= v
                    }
                    s.toFloat()
                }
                is MapTableOp -> {
                    val t = op.table
                    val n = t.size
                    applyArea(op.area, planes, width, rows, rowOffset, colOffset) { v, _, _ ->
                        val f = (v.coerceIn(0f, 1f) * (n - 1))
                        val i = f.toInt().coerceIn(0, n - 1)
                        val j = min(i + 1, n - 1)
                        val fr = f - i
                        ((t[i] + (t[j] - t[i]) * fr) / 65535f)
                    }
                }
                is PerRowColOp -> applyArea(op.area, planes, width, rows, rowOffset, colOffset) { v, r, c ->
                    val idx = if (op.perRow) (r - op.area.top) / op.area.rowPitch else (c - op.area.left) / op.area.colPitch
                    if (idx < 0 || idx >= op.values.size) v
                    else if (op.isScale) v * op.values[idx] else v + op.values[idx]
                }
                else -> {}
            }
        }
    }

    private inline fun applyArea(
        a: AreaSpec, planes: Array<FloatArray>, width: Int, rows: Int, rowOffset: Int, colOffset: Int,
        crossinline f: (Float, Int, Int) -> Float,
    ) {
        val r0 = max(a.top, rowOffset)
        val r1 = min(a.bottom, rowOffset + rows)
        val c0 = max(a.left, colOffset)
        val c1 = min(a.right, colOffset + width)
        if (r0 >= r1 || c0 >= c1) return
        for (p in a.plane until min(planes.size, a.plane + a.planes)) {
            val d = planes[p]
            var r = r0 + floorMod(a.top - r0, a.rowPitch)
            while (r < r1) {
                val rowBase = (r - rowOffset) * width - colOffset
                var c = c0 + floorMod(a.left - c0, a.colPitch)
                while (c < c1) {
                    d[rowBase + c] = f(d[rowBase + c], r, c)
                    c += a.colPitch
                }
                r += a.rowPitch
            }
        }
    }

    private fun floorMod(a: Int, m: Int): Int {
        val r = a % m
        return if (r < 0) r + m else r
    }

    private fun applyGainMap(
        op: GainMapOp, planes: Array<FloatArray>, width: Int, rows: Int,
        rowOffset: Int, colOffset: Int, imageW: Int, imageH: Int, clamp: Boolean,
    ) {
        val a = op.area
        val r0 = max(a.top, rowOffset)
        val r1 = min(a.bottom, rowOffset + rows)
        val c0 = max(a.left, colOffset)
        val c1 = min(a.right, colOffset + width)
        if (r0 >= r1 || c0 >= c1) return
        val rowStart = r0 + floorMod(a.top - r0, a.rowPitch)
        val nRows = if (rowStart >= r1) 0 else (r1 - 1 - rowStart) / a.rowPitch + 1
        val colStart = c0 + floorMod(a.left - c0, a.colPitch)
        val nCols = if (colStart >= c1) 0 else (c1 - 1 - colStart) / a.colPitch + 1
        if (nRows == 0 || nCols == 0) return
        // 列方向の補間係数は全行で共通なので先に計算
        val cIdx = IntArray(nCols)
        val cFrac = FloatArray(nCols)
        for (j in 0 until nCols) op.mapIndex(op.colCoord(colStart + j * a.colPitch, imageW), op.pointsH, cIdx, j, cFrac)
        val lastC = op.pointsH - 1
        val ph = op.pointsH
        val mpN = op.mapPlanes
        filmlab.core.util.Parallel.forChunks(nRows, 16) { s, e ->
            val rIdx = IntArray(1)
            val rFrac = FloatArray(1)
            for (p in a.plane until min(planes.size, a.plane + a.planes)) {
                val d = planes[p]
                val mp = min(p, mpN - 1)
                for (k in s until e) {
                    val r = rowStart + k * a.rowPitch
                    op.mapIndex(op.rowCoord(r, imageH), op.pointsV, rIdx, 0, rFrac)
                    val i1 = rIdx[0]
                    val i2 = min(i1 + 1, op.pointsV - 1)
                    val rf = rFrac[0]
                    val rowBase = (r - rowOffset) * width - colOffset
                    for (j in 0 until nCols) {
                        val c = colStart + j * a.colPitch
                        val j1 = cIdx[j]
                        val j2 = min(j1 + 1, lastC)
                        val cf = cFrac[j]
                        val g11 = op.gains[(i1 * ph + j1) * mpN + mp]
                        val g12 = op.gains[(i1 * ph + j2) * mpN + mp]
                        val g21 = op.gains[(i2 * ph + j1) * mpN + mp]
                        val g22 = op.gains[(i2 * ph + j2) * mpN + mp]
                        val ga = g11 + (g12 - g11) * cf
                        val gb = g21 + (g22 - g21) * cf
                        val g = ga + (gb - ga) * rf
                        val v = d[rowBase + c] * g
                        d[rowBase + c] = if (clamp) min(v, 1f) else v
                    }
                }
            }
        }
    }

    private fun applyVignette(
        op: FixVignetteRadialOp, planes: Array<FloatArray>, width: Int, rows: Int,
        rowOffset: Int, colOffset: Int, imageW: Int, imageH: Int,
    ) {
        val cx = op.cx * imageW
        val cy = op.cy * imageH
        val m = maxCornerDistance(cx, cy, imageW.toDouble(), imageH.toDouble())
        val inv2 = 1.0 / (m * m)
        val k = op.k
        filmlab.core.util.Parallel.forChunks(rows, 16) { s, e ->
            for (y in s until e) {
                val dy = (y + rowOffset) - cy
                for (x in 0 until width) {
                    val dx = (x + colOffset) - cx
                    val r2 = (dx * dx + dy * dy) * inv2
                    val g = 1.0 + r2 * (k[0] + r2 * (k[1] + r2 * (k[2] + r2 * (k[3] + r2 * k[4]))))
                    val i = y * width + x
                    for (p in planes.indices) planes[p][i] = (planes[p][i] * g).toFloat()
                }
            }
        }
    }

    fun maxCornerDistance(cx: Double, cy: Double, w: Double, h: Double): Double {
        val dx = max(cx, w - cx)
        val dy = max(cy, h - cy)
        return sqrt(dx * dx + dy * dy)
    }

    /** 不良画素リストの補間（CFA 単一プレーン、同色近傍の平均）。 */
    fun applyBadPixels(op: FixBadPixelsListOp, data: FloatArray, w: Int, h: Int, cfa: CfaPattern?, rowOffset: Int, colOffset: Int) {
        val bad = HashSet<Long>()
        for (i in 0 until op.points.size / 2) {
            val r = op.points[i * 2] - rowOffset
            val c = op.points[i * 2 + 1] - colOffset
            if (r in 0 until h && c in 0 until w) bad.add(r.toLong() * w + c)
        }
        for (i in 0 until op.rects.size / 4) {
            val t = op.rects[i * 4] - rowOffset
            val l = op.rects[i * 4 + 1] - colOffset
            val b = op.rects[i * 4 + 2] - rowOffset
            val rr = op.rects[i * 4 + 3] - colOffset
            for (r in max(0, t) until min(h, b)) for (c in max(0, l) until min(w, rr)) bad.add(r.toLong() * w + c)
        }
        if (bad.isEmpty()) return
        for (key in bad) {
            val r = (key / w).toInt()
            val c = (key % w).toInt()
            val color = cfa?.colorAt(c, r) ?: -1
            var sum = 0f
            var n = 0
            val offs = if (cfa == null) arrayOf(intArrayOf(0, -1), intArrayOf(0, 1), intArrayOf(-1, 0), intArrayOf(1, 0))
            else if (color == 1) arrayOf(intArrayOf(-1, -1), intArrayOf(-1, 1), intArrayOf(1, -1), intArrayOf(1, 1), intArrayOf(0, -2), intArrayOf(0, 2), intArrayOf(-2, 0), intArrayOf(2, 0))
            else arrayOf(intArrayOf(0, -2), intArrayOf(0, 2), intArrayOf(-2, 0), intArrayOf(2, 0), intArrayOf(-2, -2), intArrayOf(-2, 2), intArrayOf(2, -2), intArrayOf(2, 2))
            for (o in offs) {
                val rr = r + o[0]
                val cc = c + o[1]
                if (rr in 0 until h && cc in 0 until w && !bad.contains(rr.toLong() * w + cc)) {
                    sum += data[rr * w + cc]
                    n++
                }
            }
            if (n > 0) data[r * w + c] = sum / n
        }
    }
}
