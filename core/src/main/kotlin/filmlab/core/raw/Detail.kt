package filmlab.core.raw

import filmlab.core.util.Blur
import filmlab.core.util.FastMath
import filmlab.core.util.Parallel
import kotlin.math.abs
import kotlin.math.exp

/**
 * RAW 段階のディテール処理（シーン線形の作業色空間 Rec.2020 上）。
 * - 色ノイズ低減: 輝度を保ったまま色比 (R/Y, B/Y) だけを輝度ガイドのバイラテラルで平滑化
 * - キャプチャーシャープ: 対数輝度のアンシャープマスク（ハイライトでハロが出にくい）
 * どちらも画素数を変えず、輝度の解像感を損なわないように設計。
 */
object Detail {
    private const val KR = 0.2627f
    private const val KG = 0.6780f
    private const val KB = 0.0593f

    /** 必要な上下余白 */
    const val NR_RADIUS = 2
    const val SHARPEN_RADIUS = 3

    fun chromaDenoise(r: FloatArray, g: FloatArray, b: FloatArray, w: Int, rows: Int, strength: Float) {
        if (strength <= 0.01f) return
        val n = w * rows
        val eps = 0.002f
        val ly = FloatArray(n)
        val cr = FloatArray(n)
        val cb = FloatArray(n)
        val yv = FloatArray(n)
        Parallel.forChunks(rows, 16) { s, e ->
            for (i in s * w until e * w) {
                val y = KR * r[i] + KG * g[i] + KB * b[i]
                val yp = (if (y > 0f) y else 0f) + eps
                yv[i] = y
                ly[i] = FastMath.log2(yp)
                cr[i] = r[i] / yp
                cb[i] = b[i] / yp
            }
        }
        val rad = NR_RADIUS
        val sSp = 1.0f + 0.6f * strength
        val sRange = 0.18f + 0.22f * strength // log2 単位
        val spatial = FloatArray((2 * rad + 1) * (2 * rad + 1))
        for (dy in -rad..rad) for (dx in -rad..rad) {
            spatial[(dy + rad) * (2 * rad + 1) + dx + rad] = exp(-(dx * dx + dy * dy) / (2.0 * sSp * sSp)).toFloat()
        }
        val rangeLut = FloatArray(1025) { i ->
            val d = i / 1024f * 4f
            exp(-(d * d) / (2.0 * sRange * sRange)).toFloat()
        }
        val outR = FloatArray(n)
        val outB = FloatArray(n)
        Parallel.forChunks(rows, 8) { s, e ->
            for (y in s until e) {
                for (x in 0 until w) {
                    val i = y * w + x
                    val l0 = ly[i]
                    var sw = 0f
                    var sr = 0f
                    var sb = 0f
                    for (dy in -rad..rad) {
                        val yy = y + dy
                        if (yy < 0 || yy >= rows) continue
                        for (dx in -rad..rad) {
                            val xx = x + dx
                            if (xx < 0 || xx >= w) continue
                            val j = yy * w + xx
                            val d = abs(ly[j] - l0)
                            val ri = (d * 256f).toInt()
                            if (ri >= 1024) continue
                            val wt = spatial[(dy + rad) * (2 * rad + 1) + dx + rad] * rangeLut[ri]
                            sw += wt
                            sr += cr[j] * wt
                            sb += cb[j] * wt
                        }
                    }
                    outR[i] = sr / sw
                    outB[i] = sb / sw
                }
            }
        }
        Parallel.forChunks(rows, 16) { s, e ->
            for (i in s * w until e * w) {
                val y = yv[i]
                val yp = (if (y > 0f) y else 0f) + eps
                val nr = outR[i] * yp
                val nb = outB[i] * yp
                r[i] = nr
                b[i] = nb
                g[i] = (y - KR * nr - KB * nb) / KG
            }
        }
    }

    fun sharpen(r: FloatArray, g: FloatArray, b: FloatArray, w: Int, rows: Int, amount: Float, sigma: Float = 0.8f) {
        if (amount <= 0.01f) return
        val n = w * rows
        val eps = 0.001f
        val l = FloatArray(n)
        Parallel.forChunks(rows, 16) { s, e ->
            for (i in s * w until e * w) {
                val y = KR * r[i] + KG * g[i] + KB * b[i]
                l[i] = FastMath.log2((if (y > 0f) y else 0f) + eps)
            }
        }
        val bl = FloatArray(n)
        Blur.separable(l, w, rows, Blur.gaussianKernel(sigma), bl)
        val coring = 0.03f
        Parallel.forChunks(rows, 16) { s, e ->
            for (i in s * w until e * w) {
                val d = l[i] - bl[i]
                val ad = abs(d)
                val dc = d * (ad / (ad + coring)) // 微小な差（ノイズ）は強調しない
                var gain = FastMath.exp2(amount * dc)
                if (gain < 0.6f) gain = 0.6f
                if (gain > 1.6f) gain = 1.6f
                r[i] *= gain
                g[i] *= gain
                b[i] *= gain
            }
        }
    }
}
