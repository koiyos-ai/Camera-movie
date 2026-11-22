package filmlab.core

import filmlab.core.raw.BurstMerge
import java.util.Random
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

class BurstMergeTest {
    /** 手ブレ（偶数画素のずれ）とノイズを加えた連写を合成すると、真値との誤差が 1 枚より小さくなる */
    @Test
    fun alignsAndReducesNoise() {
        val w = 512
        val h = 384
        val black = 64f
        val white = 4095f
        fun truth(x: Int, y: Int): Float {
            val v = 600 + 500 * sin(x * 0.07) * sin(y * 0.05) + 400 * sin((x + y) * 0.013) + (if ((x / 40 + y / 40) % 2 == 0) 300 else 0)
            return (v + black).toFloat()
        }
        val rnd = Random(5)
        val shifts = listOf(0 to 0, 4 to -2, -6 to 2, 2 to 6)
        val frames = shifts.map { (sx, sy) ->
            ShortArray(w * h) { i ->
                val x = i % w
                val y = i / w
                val v = truth(x + sx, y + sy) + rnd.nextGaussian().toFloat() * 40f
                v.coerceIn(0f, white).toInt().toShort()
            }
        }
        val res = BurstMerge.merge(frames, w, h, black, white)
        val (rsx, rsy) = shifts[res.reference]
        fun rmse(img: ShortArray): Double {
            var se = 0.0
            var n = 0
            for (y in 32 until h - 32) for (x in 32 until w - 32) {
                val d = (img[y * w + x].toInt() and 0xFFFF) - truth(x + rsx, y + rsy)
                se += d * d
                n++
            }
            return sqrt(se / n)
        }
        val single = rmse(frames[res.reference])
        val merged = rmse(res.raw)
        assertTrue(merged < single * 0.65, "single=$single merged=$merged (${res.note})")
    }
}
