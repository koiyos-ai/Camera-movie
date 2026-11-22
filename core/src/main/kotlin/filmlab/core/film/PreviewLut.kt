package filmlab.core.film

import filmlab.core.raw.ColorMath
import kotlin.math.pow

/**
 * リアルタイムプレビュー用の 3D LUT。
 * カメラのプレビュー映像（ISP 処理済みの sRGB）を「おおよそのシーン線形」に戻し、
 * フィルムモデル（特性曲線・層間効果・プリント・色素）を通した結果を sRGB で返す表を作る。
 * 粒子・ハレーション・隣接効果は含まない（粒子はシェーダで別途加える）。
 */
object PreviewLut {
    /** n^3 の RGB8（r が最も速く変わる順。glTexImage3D にそのまま渡せる） */
    fun build(stock: FilmStock, params: DevelopParams, n: Int = 33): ByteArray {
        val p = params.copy(grain = 0f, halation = 0f)
        val count = n * n * n
        val r = FloatArray(count)
        val g = FloatArray(count)
        val b = FloatArray(count)
        // プレビューはカメラの AWB で白が合っている（≒ D65）とみなし、本現像と同じ白の扱いを色順応で再現する:
        // フィルム固有の色温度（500T の 3200K など）と、色温度・ティントのスライダー
        var white = if (p.filmWhiteBalance && stock.kelvin != null) ColorMath.xyFromCct(stock.kelvin) else ColorMath.D65_XY
        white = ColorMath.shiftWhite(white, -60.0 * p.warmth, 0.012 * p.tint)
        val cat = ColorMath.bradford(white, ColorMath.D65_XY)
        val toWork = (ColorMath.XYZ_TO_REC2020 * cat * ColorMath.SRGB_TO_XYZ).toFloatArray()
        val gain = 2.0.pow(p.exposureEv.toDouble() + stock.exposureBias).toFloat()
        val dec = FloatArray(n) { i -> expand(ColorMath.srgbEotf(i / (n - 1.0)).toFloat()) }
        for (bi in 0 until n) for (gi in 0 until n) for (ri in 0 until n) {
            val i = (bi * n + gi) * n + ri
            val lr = dec[ri]
            val lg = dec[gi]
            val lb = dec[bi]
            r[i] = (toWork[0] * lr + toWork[1] * lg + toWork[2] * lb) * gain
            g[i] = (toWork[3] * lr + toWork[4] * lg + toWork[5] * lb) * gain
            b[i] = (toWork[6] * lr + toWork[7] * lg + toWork[8] * lb) * gain
        }
        val out = ByteArray(count * 3)
        FilmRenderer(stock, p, n * n, n, 0L, spatial = false, dithering = false)
            .render(r, g, b, n * n, n, 0, 0, n, null, out, 0)
        // 出力は Display P3 エンコード → プレビュー面（sRGB）用に変換
        val eotf = FloatArray(256) { ColorMath.srgbEotf(it / 255.0).toFloat() }
        val m = (ColorMath.XYZ_TO_SRGB * ColorMath.P3_TO_XYZ).toFloatArray()
        for (i in 0 until count) {
            val a = eotf[out[i * 3].toInt() and 0xFF]
            val c = eotf[out[i * 3 + 1].toInt() and 0xFF]
            val d = eotf[out[i * 3 + 2].toInt() and 0xFF]
            out[i * 3] = enc(m[0] * a + m[1] * c + m[2] * d)
            out[i * 3 + 1] = enc(m[3] * a + m[4] * c + m[5] * d)
            out[i * 3 + 2] = enc(m[6] * a + m[7] * c + m[8] * d)
        }
        return out
    }

    /** プレビューは ISP がハイライトを圧縮しているので、明部を少し伸ばしてシーン線形に近づける */
    private fun expand(v: Float): Float = v * (1f + 1.5f * v * v * v)

    private fun enc(v: Float): Byte {
        val x = ColorMath.srgbOetf(v.coerceIn(0f, 1f).toDouble()) * 255.0 + 0.5
        return x.toInt().coerceIn(0, 255).toByte()
    }

    /** シェーダの粒子の強さ（表示値の振幅の目安） */
    fun grainStrength(stock: FilmStock, params: DevelopParams): Float =
        (stock.grainAmount * params.grain * 2.2).toFloat()
}
