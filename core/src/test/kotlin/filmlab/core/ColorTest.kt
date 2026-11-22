package filmlab.core

import filmlab.core.dng.DngMeta
import filmlab.core.film.DevelopParams
import filmlab.core.raw.ColorMath
import filmlab.core.raw.DevelopSession
import filmlab.core.raw.DngColor
import filmlab.core.util.Mat3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 色変換の検証。合成 DNG のカメラは「sRGB 線形 c → カメラ RGB = diag(sens)·A·c」として作ってあるので、
 * 現像の色変換は c の Rec.2020 表現を正確に再現しなければならない。
 */
class ColorTest {
    private val a = Mat3.of(0.80, 0.17, 0.03, 0.06, 0.86, 0.08, 0.02, 0.18, 0.80)
    private val sens = doubleArrayOf(0.52, 1.0, 0.68)
    private val colors = listOf(
        doubleArrayOf(1.0, 1.0, 1.0), doubleArrayOf(0.18, 0.18, 0.18), doubleArrayOf(0.6, 0.1, 0.05),
        doubleArrayOf(0.05, 0.4, 0.1), doubleArrayOf(0.05, 0.1, 0.5), doubleArrayOf(0.8, 0.7, 0.1),
    )

    private fun camera(c: DoubleArray): DoubleArray {
        val v = a * c
        return doubleArrayOf(v[0] * sens[0], v[1] * sens[1], v[2] * sens[2])
    }

    private fun assertClose(exp: DoubleArray, got: DoubleArray, tol: Double, msg: String) {
        for (i in 0..2) assertTrue(abs(exp[i] - got[i]) < tol, "$msg: expected ${exp.joinToString()} got ${got.joinToString()}")
    }

    @Test
    fun forwardMatrixPathReproducesSceneColors() {
        val dng = Fixtures.dng("fixture_raw.dng")
        val s = DevelopSession(dng, dng.decodeRaw())
        val cs = s.colorSetup(DevelopParams(filmWhiteBalance = false))
        val srgbToWork = ColorMath.XYZ_TO_REC2020 * ColorMath.SRGB_TO_XYZ
        for (c in colors) {
            assertClose(srgbToWork * c, cs.camToWork * camera(c), 2e-3, "FM")
        }
    }

    @Test
    fun colorMatrixOnlyPathReproducesSceneColors() {
        val cm = Mat3.diag(sens) * a * ColorMath.XYZ_TO_SRGB
        val meta = DngMeta(
            make = "", model = "", uniqueModel = "", orientation = 1, dngVersion = "1.4",
            colorMatrix1 = cm, colorMatrix2 = null, forwardMatrix1 = null, forwardMatrix2 = null,
            cameraCalibration1 = null, cameraCalibration2 = null, analogBalance = null,
            illuminant1 = 21, illuminant2 = 0, asShotNeutral = sens, asShotWhiteXY = null,
            baselineExposure = 0.0, noiseProfile = null, rawWidth = 4, rawHeight = 4,
            activeArea = intArrayOf(0, 0, 4, 4), cfa = null, samplesPerPixel = 3, bitsPerSample = 16,
            compression = 1, photometric = 34892, tiled = false, defaultCropX = 0, defaultCropY = 0,
            defaultCropW = 4, defaultCropH = 4, opcodes1 = emptyList(), opcodes2 = emptyList(),
            opcodes3 = emptyList(), hasProfileGainTableMap = false, whiteLevels = doubleArrayOf(1.0, 1.0, 1.0),
            blackLevelSummary = "",
        )
        val col = DngColor(meta)
        val n = col.asShotNeutral()
        val white = col.neutralToXy(n)
        assertTrue(abs(white[0] - 0.3127) < 2e-3 && abs(white[1] - 0.3290) < 2e-3, "white ${white.joinToString()}")
        val camToPcs = col.cameraToXyzD50(n)
        val expectM = ColorMath.bradford(ColorMath.D65_XY, ColorMath.D50_XY) * ColorMath.SRGB_TO_XYZ
        for (c in colors) assertClose(expectM * c, camToPcs * camera(c), 3e-3, "CM")
    }

    @Test
    fun whiteBalanceShiftDirection() {
        // +warmth で想定光源を高い色温度へ → 画像は暖色（R/B が増える）
        val dng = Fixtures.dng("fixture_raw.dng")
        val s = DevelopSession(dng, dng.decodeRaw())
        val grey = camera(doubleArrayOf(0.18, 0.18, 0.18))
        val base = s.colorSetup(DevelopParams(filmWhiteBalance = false)).camToWork * grey
        val warm = s.colorSetup(DevelopParams(filmWhiteBalance = false, warmth = 1f)).camToWork * grey
        assertTrue(warm[0] / warm[2] > base[0] / base[2] * 1.05, "warm ${warm.joinToString()} base ${base.joinToString()}")
    }
}
