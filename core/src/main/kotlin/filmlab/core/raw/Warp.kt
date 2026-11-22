package filmlab.core.raw

import filmlab.core.dng.Opcodes
import filmlab.core.dng.WarpRectilinearOp
import kotlin.math.floor
import kotlin.math.min

/**
 * DNG WarpRectilinear（歪曲・倍率色収差）の座標変換。dng_sdk の dng_filter_warp と同じ式:
 *   出力画素 (x, y) → 中心からの正規化距離 → 放射/接線多項式 → 元画像の座標
 */
class WarpMapper(private val op: WarpRectilinearOp, imageW: Int, imageH: Int) {
    private val cx = op.cx * imageW
    private val cy = op.cy * imageH
    private val m = Opcodes.maxCornerDistance(cx, cy, imageW.toDouble(), imageH.toDouble())
    private val invM = 1.0 / m
    val planes get() = op.planes

    /** plane（0..2、係数が 1 組なら共通）について出力座標 → 入力座標。out[0]=x, out[1]=y */
    fun map(x: Double, y: Double, plane: Int, out: DoubleArray) {
        val k = op.coeffs[min(plane, op.planes - 1)]
        val dx = (x - cx) * invM
        val dy = (y - cy) * invM
        val rr = min(dx * dx + dy * dy, 1.0)
        val ratio = k[0] + rr * (k[1] + rr * (k[2] + rr * k[3]))
        val dxdy2 = 2.0 * dx * dy
        val tx = k[4] * dxdy2 + k[5] * (rr + 2.0 * dx * dx)
        val ty = k[5] * dxdy2 + k[4] * (rr + 2.0 * dy * dy)
        out[0] = cx + m * (dx * ratio + tx)
        out[1] = cy + m * (dy * ratio + ty)
    }
}

/** バイキュービック（Catmull-Rom）補間。src は w × h、座標は画素中心が整数。 */
object Resample {
    private fun cr(t: Float, out: FloatArray) {
        val t2 = t * t
        val t3 = t2 * t
        out[0] = (-0.5f * t3 + t2 - 0.5f * t)
        out[1] = (1.5f * t3 - 2.5f * t2 + 1f)
        out[2] = (-1.5f * t3 + 2f * t2 + 0.5f * t)
        out[3] = (0.5f * t3 - 0.5f * t2)
    }

    fun bicubic(src: FloatArray, w: Int, h: Int, x: Double, y: Double, wx: FloatArray, wy: FloatArray): Float {
        val fx = floor(x)
        val fy = floor(y)
        val ix = fx.toInt()
        val iy = fy.toInt()
        cr((x - fx).toFloat(), wx)
        cr((y - fy).toFloat(), wy)
        var acc = 0f
        for (j in 0..3) {
            var yy = iy - 1 + j
            if (yy < 0) yy = 0 else if (yy >= h) yy = h - 1
            val row = yy * w
            var a = 0f
            for (i in 0..3) {
                var xx = ix - 1 + i
                if (xx < 0) xx = 0 else if (xx >= w) xx = w - 1
                a += src[row + xx] * wx[i]
            }
            acc += a * wy[j]
        }
        return acc
    }

    fun bilinear(src: FloatArray, w: Int, h: Int, x: Double, y: Double): Float {
        var fx = x
        var fy = y
        if (fx < 0) fx = 0.0
        if (fy < 0) fy = 0.0
        if (fx > w - 1) fx = (w - 1).toDouble()
        if (fy > h - 1) fy = (h - 1).toDouble()
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        val ax = (fx - x0).toFloat()
        val ay = (fy - y0).toFloat()
        val a = src[y0 * w + x0] + (src[y0 * w + x1] - src[y0 * w + x0]) * ax
        val b = src[y1 * w + x0] + (src[y1 * w + x1] - src[y1 * w + x0]) * ax
        return a + (b - a) * ay
    }
}
