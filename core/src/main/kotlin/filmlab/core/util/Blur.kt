package filmlab.core.util

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object Blur {
    fun gaussianKernel(sigma: Float): FloatArray {
        if (sigma <= 0.01f) return floatArrayOf(1f)
        val r = max(1, ceil(3.0 * sigma).toInt())
        val k = FloatArray(2 * r + 1)
        var s = 0.0
        for (i in -r..r) {
            val v = exp(-(i * i) / (2.0 * sigma * sigma))
            k[i + r] = v.toFloat()
            s += v
        }
        for (i in k.indices) k[i] = (k[i] / s).toFloat()
        return k
    }

    /** 分離可能カーネルでの 2D ぼかし（端はクランプ）。src と dst は同じでもよい。 */
    fun separable(src: FloatArray, w: Int, h: Int, kernel: FloatArray, dst: FloatArray, tmp: FloatArray = FloatArray(w * h)) {
        val r = kernel.size / 2
        if (r == 0) {
            if (src !== dst) System.arraycopy(src, 0, dst, 0, w * h)
            return
        }
        Parallel.forChunks(h, 16) { s, e ->
            for (y in s until e) {
                val row = y * w
                for (x in 0 until w) {
                    var acc = 0f
                    if (x >= r && x < w - r) {
                        var k = row + x - r
                        for (i in kernel.indices) {
                            acc += src[k] * kernel[i]
                            k++
                        }
                    } else {
                        for (i in kernel.indices) {
                            val xx = min(w - 1, max(0, x + i - r))
                            acc += src[row + xx] * kernel[i]
                        }
                    }
                    tmp[row + x] = acc
                }
            }
        }
        Parallel.forChunks(h, 16) { s, e ->
            for (y in s until e) {
                val row = y * w
                for (x in 0 until w) {
                    var acc = 0f
                    for (i in kernel.indices) {
                        val yy = min(h - 1, max(0, y + i - r))
                        acc += tmp[yy * w + x] * kernel[i]
                    }
                    dst[row + x] = acc
                }
            }
        }
    }

    /** 大きな sigma 用: 3 回の箱型ぼかしでガウスを近似（O(1)/画素）。 */
    fun fastGaussian(src: FloatArray, w: Int, h: Int, sigma: Float, dst: FloatArray) {
        if (sigma < 2f) {
            separable(src, w, h, gaussianKernel(sigma), dst)
            return
        }
        val sizes = boxSizes(sigma, 3)
        val a = FloatArray(w * h)
        val b = FloatArray(w * h)
        System.arraycopy(src, 0, a, 0, w * h)
        for (sz in sizes) {
            val rad = (sz - 1) / 2
            boxH(a, b, w, h, rad)
            boxV(b, a, w, h, rad)
        }
        System.arraycopy(a, 0, dst, 0, w * h)
    }

    private fun boxSizes(sigma: Float, n: Int): IntArray {
        val wIdeal = sqrt((12.0 * sigma * sigma / n) + 1)
        var wl = floor(wIdeal).toInt()
        if (wl % 2 == 0) wl--
        val wu = wl + 2
        val mIdeal = (12.0 * sigma * sigma - n * wl * wl - 4.0 * n * wl - 3.0 * n) / (-4.0 * wl - 4)
        val m = Math.round(mIdeal).toInt()
        return IntArray(n) { if (it < m) wl else wu }
    }

    private fun boxH(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val inv = 1f / (2 * r + 1)
        Parallel.forChunks(h, 16) { s, e ->
            for (y in s until e) {
                val row = y * w
                var acc = 0f
                for (i in -r..r) acc += src[row + min(w - 1, max(0, i))]
                for (x in 0 until w) {
                    dst[row + x] = acc * inv
                    val add = src[row + min(w - 1, x + r + 1)]
                    val sub = src[row + max(0, x - r)]
                    acc += add - sub
                }
            }
        }
    }

    private fun boxV(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val inv = 1f / (2 * r + 1)
        Parallel.forChunks(w, 32) { s, e ->
            for (x in s until e) {
                var acc = 0f
                for (i in -r..r) acc += src[min(h - 1, max(0, i)) * w + x]
                for (y in 0 until h) {
                    dst[y * w + x] = acc * inv
                    val add = src[min(h - 1, y + r + 1) * w + x]
                    val sub = src[max(0, y - r) * w + x]
                    acc += add - sub
                }
            }
        }
    }
}
