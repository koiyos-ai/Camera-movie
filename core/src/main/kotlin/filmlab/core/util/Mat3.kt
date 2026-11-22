package filmlab.core.util

import kotlin.math.abs

/** 3x3 行列（row-major, Double）。色変換の計算用。 */
class Mat3(val m: DoubleArray) {
    init {
        require(m.size == 9) { "Mat3 needs 9 elements" }
    }

    operator fun get(r: Int, c: Int): Double = m[r * 3 + c]

    operator fun times(o: Mat3): Mat3 {
        val r = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) {
            var s = 0.0
            for (k in 0..2) s += m[i * 3 + k] * o.m[k * 3 + j]
            r[i * 3 + j] = s
        }
        return Mat3(r)
    }

    operator fun times(v: DoubleArray): DoubleArray = DoubleArray(3) { i ->
        m[i * 3] * v[0] + m[i * 3 + 1] * v[1] + m[i * 3 + 2] * v[2]
    }

    operator fun plus(o: Mat3): Mat3 = Mat3(DoubleArray(9) { m[it] + o.m[it] })

    fun scale(s: Double): Mat3 = Mat3(DoubleArray(9) { m[it] * s })

    fun transpose(): Mat3 = Mat3(DoubleArray(9) { val r = it / 3; val c = it % 3; m[c * 3 + r] })

    fun determinant(): Double =
        m[0] * (m[4] * m[8] - m[5] * m[7]) -
            m[1] * (m[3] * m[8] - m[5] * m[6]) +
            m[2] * (m[3] * m[7] - m[4] * m[6])

    fun inverse(): Mat3 {
        val det = determinant()
        if (abs(det) < 1e-300) throw IllegalArgumentException("singular matrix")
        val inv = 1.0 / det
        return Mat3(
            doubleArrayOf(
                (m[4] * m[8] - m[5] * m[7]) * inv,
                (m[2] * m[7] - m[1] * m[8]) * inv,
                (m[1] * m[5] - m[2] * m[4]) * inv,
                (m[5] * m[6] - m[3] * m[8]) * inv,
                (m[0] * m[8] - m[2] * m[6]) * inv,
                (m[2] * m[3] - m[0] * m[5]) * inv,
                (m[3] * m[7] - m[4] * m[6]) * inv,
                (m[1] * m[6] - m[0] * m[7]) * inv,
                (m[0] * m[4] - m[1] * m[3]) * inv,
            )
        )
    }

    fun toFloatArray(): FloatArray = FloatArray(9) { m[it].toFloat() }

    /** 各行の和が 1 になるよう正規化（無彩色を保つ行列にする）。 */
    fun normalizeRows(): Mat3 {
        val r = m.copyOf()
        for (i in 0..2) {
            val s = r[i * 3] + r[i * 3 + 1] + r[i * 3 + 2]
            if (abs(s) > 1e-12) for (j in 0..2) r[i * 3 + j] /= s
        }
        return Mat3(r)
    }

    override fun toString(): String = (0..2).joinToString(prefix = "[", postfix = "]") { r ->
        (0..2).joinToString(prefix = "[", postfix = "]") { c -> "%.5f".format(m[r * 3 + c]) }
    }

    companion object {
        val IDENTITY = Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))

        fun diag(a: Double, b: Double, c: Double) = Mat3(doubleArrayOf(a, 0.0, 0.0, 0.0, b, 0.0, 0.0, 0.0, c))

        fun diag(v: DoubleArray) = diag(v[0], v[1], v[2])

        fun of(vararg v: Double): Mat3 = Mat3(v.copyOf())

        fun lerp(a: Mat3, b: Mat3, t: Double): Mat3 = Mat3(DoubleArray(9) { a.m[it] * t + b.m[it] * (1 - t) })
    }
}
