package filmlab.core.raw

import filmlab.core.dng.DngMeta
import filmlab.core.util.Mat3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/** 色空間の基本計算（xy / XYZ / 原色行列 / 色順応 / 色温度） */
object ColorMath {
    val D50_XY = doubleArrayOf(0.34567, 0.35850)
    val D65_XY = doubleArrayOf(0.31270, 0.32900)

    fun xyToXYZ(xy: DoubleArray): DoubleArray = doubleArrayOf(xy[0] / xy[1], 1.0, (1.0 - xy[0] - xy[1]) / xy[1])

    fun xyzToXy(v: DoubleArray): DoubleArray {
        val s = v[0] + v[1] + v[2]
        return if (s <= 0) D50_XY.copyOf() else doubleArrayOf(v[0] / s, v[1] / s)
    }

    /** RGB(原色 xy, 白色点 xy) → XYZ の行列 */
    fun rgbToXyz(r: DoubleArray, g: DoubleArray, b: DoubleArray, white: DoubleArray): Mat3 {
        val xr = xyToXYZ(r)
        val xg = xyToXYZ(g)
        val xb = xyToXYZ(b)
        val p = Mat3(doubleArrayOf(xr[0], xg[0], xb[0], xr[1], xg[1], xb[1], xr[2], xg[2], xb[2]))
        val s = p.inverse() * xyToXYZ(white)
        return p * Mat3.diag(s)
    }

    val REC2020_TO_XYZ: Mat3 = rgbToXyz(
        doubleArrayOf(0.708, 0.292), doubleArrayOf(0.170, 0.797), doubleArrayOf(0.131, 0.046), D65_XY,
    )
    val XYZ_TO_REC2020: Mat3 = REC2020_TO_XYZ.inverse()
    val P3_TO_XYZ: Mat3 = rgbToXyz(
        doubleArrayOf(0.680, 0.320), doubleArrayOf(0.265, 0.690), doubleArrayOf(0.150, 0.060), D65_XY,
    )
    val XYZ_TO_P3: Mat3 = P3_TO_XYZ.inverse()
    val SRGB_TO_XYZ: Mat3 = rgbToXyz(
        doubleArrayOf(0.640, 0.330), doubleArrayOf(0.300, 0.600), doubleArrayOf(0.150, 0.060), D65_XY,
    )
    val XYZ_TO_SRGB: Mat3 = SRGB_TO_XYZ.inverse()

    private val BRADFORD = Mat3.of(
        0.8951, 0.2664, -0.1614,
        -0.7502, 1.7135, 0.0367,
        0.0389, -0.0685, 1.0296,
    )
    private val BRADFORD_INV = BRADFORD.inverse()

    /** Bradford 色順応: srcWhite の XYZ を dstWhite の XYZ へ写す行列 */
    fun bradford(srcWhiteXy: DoubleArray, dstWhiteXy: DoubleArray): Mat3 {
        val s = BRADFORD * xyToXYZ(srcWhiteXy)
        val d = BRADFORD * xyToXYZ(dstWhiteXy)
        return BRADFORD_INV * Mat3.diag(d[0] / s[0], d[1] / s[1], d[2] / s[2]) * BRADFORD
    }

    /** McCamy の近似式による相関色温度 */
    fun cctFromXy(xy: DoubleArray): Double {
        val n = (xy[0] - 0.3320) / (0.1858 - xy[1])
        val t = 449.0 * n * n * n + 3525.0 * n * n + 6823.3 * n + 5520.33
        return t.coerceIn(1500.0, 50000.0)
    }

    /** 色温度 → xy。4000K 以上は CIE 昼光軌跡、未満はプランク軌跡（Kim らの近似）。 */
    fun xyFromCct(t: Double): DoubleArray {
        val tt = t.coerceIn(1667.0, 25000.0)
        if (tt >= 4000.0) {
            val x = if (tt <= 7000.0) {
                -4.6070e9 / (tt * tt * tt) + 2.9678e6 / (tt * tt) + 0.09911e3 / tt + 0.244063
            } else {
                -2.0064e9 / (tt * tt * tt) + 1.9018e6 / (tt * tt) + 0.24748e3 / tt + 0.237040
            }
            val y = -3.0 * x * x + 2.870 * x - 0.275
            return doubleArrayOf(x, y)
        }
        val x = -0.2661239e9 / (tt * tt * tt) - 0.2343589e6 / (tt * tt) + 0.8776956e3 / tt + 0.179910
        val y = if (tt <= 2222.0) {
            -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
        } else {
            -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867
        }
        return doubleArrayOf(x, y)
    }

    /**
     * 白色点 xy を色温度方向（ミレッド）と緑-マゼンタ方向（CIE1960 の v 方向）にずらす。
     * miredShift > 0 で低い色温度側（黄み）へ、greenShift > 0 で緑側へ。
     */
    fun shiftWhite(xy: DoubleArray, miredShift: Double, greenShift: Double): DoubleArray {
        var x = xy[0]
        var y = xy[1]
        if (miredShift != 0.0) {
            val t = cctFromXy(xy)
            val mired = (1e6 / t + miredShift).coerceIn(1e6 / 25000.0, 1e6 / 1667.0)
            val base = xyFromCct(t)
            val target = xyFromCct(1e6 / mired)
            // 元の白と軌跡のずれ（ティント成分）は保ったまま平行移動
            x += target[0] - base[0]
            y += target[1] - base[1]
        }
        if (greenShift != 0.0) {
            val denom = -2 * x + 12 * y + 3
            val u = 4 * x / denom
            val v = 6 * y / denom + greenShift
            val den2 = 2 * u - 8 * v + 4
            x = 3 * u / den2
            y = 2 * v / den2
        }
        return doubleArrayOf(x, y)
    }

    /** EXIF LightSource → 色温度（dng_sdk と同じ対応） */
    fun illuminantToTemperature(code: Int): Double = when (code) {
        1, 4, 9, 18 -> 5500.0 // Daylight, Flash, Fine weather, Standard light B 相当
        2 -> 4150.0 // Fluorescent
        3 -> 2850.0 // Tungsten
        10 -> 6500.0 // Cloudy
        11 -> 7500.0 // Shade
        12 -> 6430.0 // Daylight fluorescent
        13 -> 5000.0 // Day white fluorescent
        14 -> 4150.0 // Cool white fluorescent
        15 -> 3450.0 // White fluorescent
        16 -> 2940.0 // Warm white fluorescent
        17 -> 2856.0 // Standard light A
        19 -> 6774.0 // Standard light C
        20 -> 5503.0 // D55
        21 -> 6504.0 // D65
        22 -> 7504.0 // D75
        23 -> 5003.0 // D50
        24 -> 3200.0 // ISO studio tungsten
        else -> 0.0
    }

    fun srgbOetf(v: Double): Double = if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1.0 / 2.4) - 0.055
    fun srgbEotf(v: Double): Double = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
}

/**
 * DNG 仕様の「カメラ色空間 → XYZ(D50)」変換。
 * ForwardMatrix がある場合はそれを使い、無い場合は ColorMatrix の逆行列 + Bradford 順応。
 * 2 つのキャリブレーション光源の間は色温度の逆数で補間する。
 */
class DngColor(private val meta: DngMeta) {
    private val t1: Double
    private val t2: Double
    private val cm1: Mat3?
    private val cm2: Mat3?
    private val fm1: Mat3?
    private val fm2: Mat3?
    private val cc1: Mat3
    private val cc2: Mat3
    private val ab: Mat3 = meta.analogBalance?.let { Mat3.diag(it) } ?: Mat3.IDENTITY

    init {
        var a = ColorMath.illuminantToTemperature(meta.illuminant1)
        var b = ColorMath.illuminantToTemperature(meta.illuminant2)
        var m1 = meta.colorMatrix1
        var m2 = meta.colorMatrix2
        var f1 = meta.forwardMatrix1
        var f2 = meta.forwardMatrix2
        var c1 = meta.cameraCalibration1 ?: Mat3.IDENTITY
        var c2 = meta.cameraCalibration2 ?: Mat3.IDENTITY
        if (m1 == null && m2 != null) {
            m1 = m2; f1 = f2; c1 = c2; a = b
            m2 = null; f2 = null
        }
        if (m1 == null) {
            // 色行列が無い DNG: sRGB 相当とみなす（暫定）
            m1 = ColorMath.XYZ_TO_SRGB * ColorMath.bradford(ColorMath.D50_XY, ColorMath.D65_XY)
            a = 6504.0
        }
        if (m2 != null && a > b && b > 0) {
            // t1 < t2 になるよう入れ替え
            val tm = m1; m1 = m2; m2 = tm
            val tf = f1; f1 = f2; f2 = tf
            val tc = c1; c1 = c2; c2 = tc
            val tt = a; a = b; b = tt
        }
        t1 = a; t2 = b
        cm1 = m1; cm2 = m2; fm1 = f1; fm2 = f2; cc1 = c1; cc2 = c2
    }

    private fun weight(t: Double): Double {
        if (cm2 == null || t1 <= 0 || t2 <= 0 || t1 == t2) return 1.0
        if (t <= t1) return 1.0
        if (t >= t2) return 0.0
        return (1.0 / t - 1.0 / t2) / (1.0 / t1 - 1.0 / t2)
    }

    private fun interp(a: Mat3?, b: Mat3?, g: Double): Mat3? = when {
        a == null -> b
        b == null -> a
        else -> Mat3.lerp(a, b, g)
    }

    fun xyzToCamera(whiteXy: DoubleArray): Mat3 {
        val g = weight(ColorMath.cctFromXy(whiteXy))
        val cm = interp(cm1, cm2, g)!!
        val cc = interp(cc1, cc2, g)!!
        return ab * cc * cm
    }

    /** カメラニュートラル（白の RGB）→ 白色点 xy（反復解法、dng_sdk と同じ） */
    fun neutralToXy(neutral: DoubleArray): DoubleArray {
        var last = ColorMath.D50_XY.copyOf()
        for (pass in 0 until 30) {
            val m = xyzToCamera(last)
            val next = ColorMath.xyzToXy(m.inverse() * neutral)
            if (abs(next[0] - last[0]) + abs(next[1] - last[1]) < 1e-7) return next
            if (pass == 29) return doubleArrayOf((last[0] + next[0]) * 0.5, (last[1] + next[1]) * 0.5)
            last = next
        }
        return last
    }

    /** 白色点 xy → カメラニュートラル（最大成分 1） */
    fun xyToNeutral(xy: DoubleArray): DoubleArray {
        val n = xyzToCamera(xy) * ColorMath.xyToXYZ(xy)
        val mx = max(n[0], max(n[1], n[2]))
        return doubleArrayOf(n[0] / mx, n[1] / mx, n[2] / mx)
    }

    fun asShotNeutral(): DoubleArray {
        meta.asShotNeutral?.let { n ->
            val mx = max(n[0], max(n[1], n[2]))
            if (mx > 0 && n.all { it > 0 }) return doubleArrayOf(n[0] / mx, n[1] / mx, n[2] / mx)
        }
        meta.asShotWhiteXY?.let { return xyToNeutral(it) }
        return xyToNeutral(ColorMath.D50_XY)
    }

    /** ニュートラルを与えたときの カメラRGB → XYZ(D50) 行列（ニュートラルが D50 白へ写る） */
    fun cameraToXyzD50(neutral: DoubleArray): Mat3 {
        val white = neutralToXy(neutral)
        val g = weight(ColorMath.cctFromXy(white))
        val fm = interp(fm1, fm2, g)
        val cc = interp(cc1, cc2, g)!!
        if (fm != null) {
            val toRef = (ab * cc).inverse()
            val refNeutral = toRef * neutral
            val d = Mat3.diag(1.0 / refNeutral[0], 1.0 / refNeutral[1], 1.0 / refNeutral[2])
            return fm * d * toRef
        }
        val cm = interp(cm1, cm2, g)!!
        var pcsToCamera = ab * cc * cm * ColorMath.bradford(ColorMath.D50_XY, white)
        val d50 = ColorMath.xyToXYZ(ColorMath.D50_XY)
        val v = pcsToCamera * d50
        val scale = max(v[0], max(v[1], v[2]))
        pcsToCamera = pcsToCamera.scale(1.0 / scale)
        return pcsToCamera.inverse()
    }
}
