package filmlab.core.film

import filmlab.core.util.Blur
import filmlab.core.util.Parallel
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 解像度に依存しないフィルム粒子のノイズ場。
 * - 画像の絶対座標からハッシュで白色ノイズを作るので、ストリップ（帯）単位で処理しても継ぎ目が出ない
 * - 粒子サイズは「35mm 判の長辺 36mm に対する µm」で指定し、出力解像度に応じて画素単位へ換算
 * - 画素より小さい粒子は振幅を減衰させ、縮小表示（プレビュー）と等倍出力で見え方が揃うようにする
 */
class GrainField(private val seed: Long) {

    private fun mix(z0: Long): Long {
        var z = z0
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    /** 単位分散の近似ガウス白色ノイズ（4 個の一様乱数の和） */
    fun white(x: Int, y: Int, channel: Int): Float {
        val h = mix((y.toLong() shl 32) xor (x.toLong() and 0xFFFFFFFFL) xor (seed + channel * -0x61c8864680b583ebL))
        val s = (h and 0xFFFF) + ((h ushr 16) and 0xFFFF) + ((h ushr 32) and 0xFFFF) + ((h ushr 48) and 0xFFFF)
        // 平均 2*65535, 分散 4 * 65536^2/12
        return ((s - 131070L).toFloat() / 65536f) * 1.7320508f
    }

    /**
     * 領域 [x0, x0+w) × [y0, y0+h)（画像の絶対座標）の相関ノイズを out に書く。
     * sigmaPx: 粒子の相関長（画素）。結果は「その解像度で見たときの」標準偏差に正規化される。
     */
    fun generate(x0: Int, y0: Int, w: Int, h: Int, sigmaPx: Float, channel: Int, out: FloatArray) {
        val amp = resolutionFactor(sigmaPx)
        if (sigmaPx < 0.5f) {
            Parallel.forChunks(h, 16) { s, e ->
                for (yy in s until e) {
                    val row = yy * w
                    val y = y0 + yy
                    for (xx in 0 until w) out[row + xx] = white(x0 + xx, y, channel) * amp
                }
            }
            return
        }
        val k = Blur.gaussianKernel(sigmaPx)
        val r = k.size / 2
        var norm = 0f
        for (v in k) norm += v * v
        // 分離カーネルなので 2D の二乗和は norm^2、標準偏差は norm
        val scale = amp / norm
        val ew = w + 2 * r
        val eh = h + 2 * r
        // 横方向: 拡張領域の白色ノイズを行ごとに生成してから畳み込み（縦の拡張分も含む）
        val hor = FloatArray(w * eh)
        Parallel.forChunks(eh, 8) { s, e ->
            val line = FloatArray(ew)
            for (yy in s until e) {
                val y = y0 - r + yy
                for (xx in 0 until ew) line[xx] = white(x0 - r + xx, y, channel)
                val row = yy * w
                for (xx in 0 until w) {
                    var acc = 0f
                    for (i in k.indices) acc += line[xx + i] * k[i]
                    hor[row + xx] = acc
                }
            }
        }
        Parallel.forChunks(h, 8) { s, e ->
            for (yy in s until e) {
                val row = yy * w
                for (xx in 0 until w) {
                    var acc = 0f
                    var p = yy * w + xx
                    for (i in k.indices) {
                        acc += hor[p] * k[i]
                        p += w
                    }
                    out[row + xx] = acc * scale
                }
            }
        }
    }

    companion object {
        /** 相関長 sigma(画素) の粒子を画素で平均したときの標準偏差の比（面積 4πσ² の粒子場） */
        fun resolutionFactor(sigmaPx: Float): Float {
            val a = 4.0 * PI * sigmaPx * sigmaPx
            return sqrt(a / (a + 1.0)).toFloat()
        }

        fun sigmaPx(grainUm: Double, longSidePx: Int): Float = max(0.0, grainUm / 36000.0 * longSidePx).toFloat()
    }
}
