package filmlab.core

import filmlab.core.film.DevelopParams
import filmlab.core.film.FilmRenderer
import filmlab.core.film.FilmStock
import filmlab.core.film.GrainField
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FilmTest {
    private fun renderUniform(stock: FilmStock, v: Float, params: DevelopParams = DevelopParams(stockId = stock.id, grain = 0f, halation = 0f)): IntArray {
        val w = 16
        val h = 16
        val r = FloatArray(w * h) { v }
        val g = FloatArray(w * h) { v }
        val b = FloatArray(w * h) { v }
        val out = ByteArray(w * h * 3)
        FilmRenderer(stock, params, w, h, 1L).render(r, g, b, w, h, 0, 0, h, null, out, 0)
        val i = (8 * w + 8) * 3
        return intArrayOf(out[i].toInt() and 0xFF, out[i + 1].toInt() and 0xFF, out[i + 2].toInt() and 0xFF)
    }

    /** 中間グレーは（意図したカラータイミング分を除き）ほぼ無彩色で、明るさは中間調になる */
    @Test
    fun midGreyStaysNeutral() {
        for (st in FilmStock.ALL) {
            val c = renderUniform(st, 0.18f)
            val spread = c.max() - c.min()
            // ウォーム系はカラータイミングで意図的に暖色へ寄せている
            assertTrue(spread <= 18, "${st.id}: ${c.joinToString()}")
            assertTrue(c.average() in 70.0..170.0, "${st.id}: ${c.joinToString()}")
        }
    }

    /** 露光を上げると単調に明るくなる（階調の反転がない） */
    @Test
    fun toneIsMonotonic() {
        for (st in FilmStock.ALL) {
            val r = FilmRenderer(st, DevelopParams(stockId = st.id), 64, 64, 1L)
            var last = -1.0
            var e = 1e-5
            while (e < 500) {
                val y = r.neutralDisplayY(e)
                assertTrue(y >= last - 1e-9, "${st.id} not monotonic at $e")
                last = y
                e *= 1.25
            }
            // 逆算（自動露出用）が往復で一致する
            val s = r.sceneForDisplayY(0.2)
            assertTrue(abs(r.neutralDisplayY(s) - 0.2) < 1e-3, "${st.id} inverse")
        }
    }

    @Test
    fun grainIsSeamlessAcrossStrips() {
        val gf = GrainField(42L)
        val a = FloatArray(64 * 40)
        val b = FloatArray(64 * 40)
        gf.generate(0, 100, 64, 40, 1.7f, 0, a)
        gf.generate(0, 120, 64, 40, 1.7f, 0, b)
        // 行 120..139 は両方に含まれ、値が完全に一致する
        for (y in 0 until 20) for (x in 0 until 64) assertEquals(a[(y + 20) * 64 + x], b[y * 64 + x])
    }

    @Test
    fun grainVarianceIsNormalized() {
        val gf = GrainField(7L)
        val n = 256
        for (sigma in floatArrayOf(0.3f, 1.0f, 2.5f)) {
            val f = FloatArray(n * n)
            gf.generate(0, 0, n, n, sigma, 0, f)
            val mean = f.average()
            val sd = sqrt(f.map { (it - mean) * (it - mean) }.average())
            val expect = GrainField.resolutionFactor(sigma)
            assertTrue(abs(sd - expect) / expect < 0.08, "sigma=$sigma sd=$sd expect=$expect")
        }
    }
}
