package filmlab.core.util

import kotlin.math.ln
import kotlin.math.pow

/** 画素ループ用の高速 log2 / exp2（テーブル + 線形補間、誤差 ~1e-6） */
object FastMath {
    private const val MANT_BITS = 10
    private const val MANT_SIZE = 1 shl MANT_BITS
    private val log2Mant = FloatArray(MANT_SIZE + 1) { i -> (ln(1.0 + i.toDouble() / MANT_SIZE) / ln(2.0)).toFloat() }
    private val exp2Frac = FloatArray(MANT_SIZE + 1) { i -> 2.0.pow(i.toDouble() / MANT_SIZE).toFloat() }

    /** x > 0 を想定。x <= 0 は -126 程度を返す。 */
    fun log2(x: Float): Float {
        val v = if (x > 1.1754944e-38f) x else 1.1754944e-38f
        val bits = java.lang.Float.floatToRawIntBits(v)
        val e = ((bits ushr 23) and 0xFF) - 127
        val mant = bits and 0x7FFFFF
        val idx = mant ushr (23 - MANT_BITS)
        val frac = (mant and ((1 shl (23 - MANT_BITS)) - 1)) * (1f / (1 shl (23 - MANT_BITS)))
        val a = log2Mant[idx]
        return e + a + (log2Mant[idx + 1] - a) * frac
    }

    fun exp2(x: Float): Float {
        if (x < -126f) return 0f
        if (x > 127f) return Float.MAX_VALUE
        val fl = kotlin.math.floor(x)
        val i = fl.toInt()
        val f = (x - fl) * MANT_SIZE
        val idx = f.toInt().coerceIn(0, MANT_SIZE - 1)
        val fr = f - idx
        val a = exp2Frac[idx]
        val m = a + (exp2Frac[idx + 1] - a) * fr
        return java.lang.Float.intBitsToFloat((i + 127) shl 23) * m
    }

    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}

/** 等間隔サンプルの 1D LUT（線形補間、範囲外はクランプ） */
class Lut1D(val x0: Float, val x1: Float, val values: FloatArray) {
    private val n = values.size
    private val scale = (n - 1) / (x1 - x0)

    fun eval(x: Float): Float {
        var f = (x - x0) * scale
        if (f <= 0f) return values[0]
        if (f >= n - 1) return values[n - 1]
        val i = f.toInt()
        f -= i
        val a = values[i]
        return a + (values[i + 1] - a) * f
    }

    companion object {
        fun build(x0: Double, x1: Double, n: Int, f: (Double) -> Double): Lut1D =
            Lut1D(x0.toFloat(), x1.toFloat(), FloatArray(n) { i -> f(x0 + (x1 - x0) * i / (n - 1)).toFloat() })
    }
}

/**
 * 正の値 x を浮動小数点のビット列でインデックスする LUT。
 * 1 オクターブあたり 2^bits 点で、log を計算せずに対数的な分解能が得られる（露光量→濃度の変換用）。
 */
class LogLut(minValue: Float, maxValue: Float, private val bits: Int, f: (Double) -> Double) {
    private val shift = 23 - bits
    private val base = java.lang.Float.floatToRawIntBits(minValue)
    private val top = java.lang.Float.floatToRawIntBits(maxValue)
    private val count = ((top - base) ushr shift) + 2
    private val values = FloatArray(count) { i ->
        val b = base + (i shl shift)
        f(java.lang.Float.intBitsToFloat(b).toDouble()).toFloat()
    }
    private val fracScale = 1f / (1 shl shift)
    private val minV = minValue
    private val maxV = maxValue

    fun eval(x: Float): Float {
        val v = if (x < minV) minV else if (x > maxV) maxV else x
        val d = java.lang.Float.floatToRawIntBits(v) - base
        val i = d ushr shift
        val fr = (d and ((1 shl shift) - 1)) * fracScale
        val a = values[i]
        return a + (values[i + 1] - a) * fr
    }
}
