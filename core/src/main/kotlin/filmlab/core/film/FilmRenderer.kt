package filmlab.core.film

import filmlab.core.raw.ColorMath
import filmlab.core.util.Blur
import filmlab.core.util.Lut1D
import filmlab.core.util.LogLut
import filmlab.core.util.Mat3
import filmlab.core.util.Parallel
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** 低解像度で計算したハレーション（にじみ光量）を、任意解像度の座標で双線形サンプルする */
class HalationField(val map: FloatArray, val mw: Int, val mh: Int, imageW: Int, imageH: Int) {
    private val sx = mw.toFloat() / imageW
    private val sy = mh.toFloat() / imageH

    fun sample(x: Int, y: Int): Float {
        var fx = (x + 0.5f) * sx - 0.5f
        var fy = (y + 0.5f) * sy - 0.5f
        if (fx < 0f) fx = 0f
        if (fy < 0f) fy = 0f
        if (fx > mw - 1f) fx = mw - 1f
        if (fy > mh - 1f) fy = mh - 1f
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val x1 = min(x0 + 1, mw - 1)
        val y1 = min(y0 + 1, mh - 1)
        val ax = fx - x0
        val ay = fy - y0
        val a = map[y0 * mw + x0] + (map[y0 * mw + x1] - map[y0 * mw + x0]) * ax
        val b = map[y1 * mw + x0] + (map[y1 * mw + x1] - map[y1 * mw + x0]) * ax
        return a + (b - a) * ay
    }
}

/**
 * フィルムの物理モデルによる描画:
 *  シーン線形 RGB → (ハレーション) → 各層の露光 → ネガ特性曲線 → 層間効果 → 隣接効果 → 粒子
 *  → プリント特性曲線（ネガ系）→ 色素の副吸収 → 透過率 → Display P3 → sRGB トーンカーブで 8bit
 *
 * imageW/imageH は「この解像度で描画するときの画像全体の大きさ」。粒子・隣接効果の半径はこれに比例。
 */
class FilmRenderer(val stock: FilmStock, val params: DevelopParams, val imageW: Int, val imageH: Int, seed: Long) {
    private val proc = stock.process
    private val longSide = max(imageW, imageH)
    private val mono = proc == FilmProcess.BW

    private val contrastMul = (1.0 + 0.35 * params.contrast).coerceIn(0.5, 1.6)
    private val dirA = (stock.interlayer + 0.25 * params.saturation).coerceIn(-0.25, 0.8)

    private val sens = stock.sensitivity.toFloatArray()
    private val bw = stock.bwWeights.map { it.toFloat() }.toFloatArray()
    private val dirM: FloatArray = Mat3.of(
        1 + dirA, -dirA / 2, -dirA / 2,
        -dirA / 2, 1 + dirA, -dirA / 2,
        -dirA / 2, -dirA / 2, 1 + dirA,
    ).toFloatArray()

    private fun softplus(z: Double): Double = if (z > 30) z else if (z < -30) exp(z) else ln(1 + exp(z))

    /** 露光 u（log10, 中間グレー=0）→ 濃度 */
    fun density(c: Int, u: Double): Double {
        val st = stock.toeSoftness
        val ss = stock.shoulderSoftness
        val ut = stock.toe[c]
        val us = stock.shoulder[c]
        val f = st * softplus((u - ut) / st) - ss * softplus((u - us) / ss)
        return if (proc == FilmProcess.REVERSAL) {
            val g = stock.gamma[c] * (1.0 + 0.25 * params.contrast)
            g * (us - ut) - g * f
        } else {
            stock.gamma[c] * f
        }
    }

    private val negLut: Array<LogLut> = Array(if (mono) 1 else 3) { c ->
        LogLut(1e-7f, 2048f, 9) { e -> density(c, log10(e / 0.18)) }
    }

    /** 中間グレー（無彩色）の層間効果後の濃度。プリントの露光基準（プリンターライト）になる */
    private val dMid = DoubleArray(3)

    init {
        val d = DoubleArray(3) { c -> density(if (mono) 0 else c, 0.0) }
        if (mono) {
            dMid[0] = d[0]; dMid[1] = d[0]; dMid[2] = d[0]
        } else {
            for (i in 0..2) dMid[i] = dirM[i * 3] * d[0] + dirM[i * 3 + 1] * d[1] + dirM[i * 3 + 2] * d[2]
        }
    }

    private val dMidF = FloatArray(3) { dMid[it].toFloat() }
    private val offF = FloatArray(3) { stock.printerOffset[it].toFloat() }

    private fun printCurve(c: Int): Lut1D {
        val dpMin = stock.printDmin
        val dpMax = stock.printDmax
        val range = dpMax - dpMin
        val k = 4.0 * stock.printGamma[c] * contrastMul / range
        val q = ((stock.printMid - dpMin) / range).coerceIn(0.02, 0.98)
        val v0 = -ln(q / (1 - q)) / k
        return Lut1D.build(-5.0, 5.0, 4096) { v -> dpMin + range / (1 + exp(-k * (v - v0))) }
    }

    private val printLut: Array<Lut1D>? = if (proc == FilmProcess.REVERSAL) null else Array(if (mono) 1 else 3) { printCurve(it) }
    private val dye = stock.dye.toFloatArray()
    private val baseD = if (proc == FilmProcess.REVERSAL) 0f else stock.printDmin.toFloat()

    /** 10^-x */
    private val transLut = Lut1D.build(-0.5, 5.0, 8192) { x -> 10.0.pow(-x) }

    private val toP3 = (ColorMath.XYZ_TO_P3 * ColorMath.REC2020_TO_XYZ).toFloatArray()
    private val oetf = Lut1D.build(0.0, 1.0, 16384) { v -> ColorMath.srgbOetf(v) * 255.0 }

    // ---- ハレーション ----
    private val halK = (stock.halation * params.halation).toFloat()
    private val halR = halK * 1.0f
    private val halG = halK * 0.32f
    private val halB = halK * 0.06f

    // ---- 粒子 ----
    private val grainField = GrainField(seed)
    private val grainAmp = (stock.grainAmount * params.grain).toFloat()
    private val grainSigma = GrainField.sigmaPx(stock.grainSizeUm * params.grainSize, longSide)
    private val layerAmp = floatArrayOf(0.9f, 1.0f, 1.35f)
    private val layerSize = floatArrayOf(1.0f, 1.0f, 1.2f)
    private val grainChroma = stock.grainChroma.toFloat().coerceIn(0f, 1f)

    // ---- 隣接効果 ----
    private val adjAmount = stock.adjacency.toFloat()
    private val adjSigma = (stock.adjacencyRadius * longSide).toFloat()

    /** 帯の上下に必要な余白（行数）。隣接効果のぼかし半径 */
    val margin: Int get() = if (adjAmount > 0f && adjSigma >= 0.3f) (3 * adjSigma).toInt() + 2 else 0

    /** ハレーション光源のしきい値（シーン線形） */
    val halationThreshold: Float get() = stock.halationThreshold.toFloat()
    val halationEnabled: Boolean get() = halK > 0.0001f

    /**
     * 作業帯を描画する。r/g/b: シーン線形（露出ゲイン適用済み）、w × rows。内容は破壊される。
     * absY0: 作業帯の先頭行の画像内 y。出力は作業帯の [outStart, outStart+outRows) 行。
     * out: RGB 8bit（outRows*w*3）を outOffset から書く。
     */
    fun render(
        r: FloatArray, g: FloatArray, b: FloatArray, w: Int, rows: Int, absY0: Int,
        outStart: Int, outRows: Int, halation: HalationField?, out: ByteArray, outOffset: Int,
    ) {
        // A. 露光 → 濃度（層間効果込み）
        Parallel.forChunks(rows, 8) { s, e ->
            for (yy in s until e) {
                val y = absY0 + yy
                val row = yy * w
                for (x in 0 until w) {
                    val i = row + x
                    val h = if (halation != null) halation.sample(x, y) else 0f
                    if (mono) {
                        val ev = bw[0] * r[i] + bw[1] * g[i] + bw[2] * b[i] + halK * 0.5f * h
                        r[i] = negLut[0].eval(ev)
                    } else {
                        val rr = r[i]
                        val gg = g[i]
                        val bb = b[i]
                        val e0 = sens[0] * rr + sens[1] * gg + sens[2] * bb + halR * h
                        val e1 = sens[3] * rr + sens[4] * gg + sens[5] * bb + halG * h
                        val e2 = sens[6] * rr + sens[7] * gg + sens[8] * bb + halB * h
                        val d0 = negLut[0].eval(e0)
                        val d1 = negLut[1].eval(e1)
                        val d2 = negLut[2].eval(e2)
                        r[i] = dirM[0] * d0 + dirM[1] * d1 + dirM[2] * d2
                        g[i] = dirM[3] * d0 + dirM[4] * d1 + dirM[5] * d2
                        b[i] = dirM[6] * d0 + dirM[7] * d1 + dirM[8] * d2
                    }
                }
            }
        }

        // B. 隣接効果（濃度の局所コントラスト。現像液の疲労によるエッジ強調）
        if (margin > 0) {
            val m = FloatArray(w * rows)
            if (mono) {
                System.arraycopy(r, 0, m, 0, w * rows)
            } else {
                for (i in 0 until w * rows) m[i] = (r[i] + g[i] + b[i]) * (1f / 3f)
            }
            val bl = FloatArray(w * rows)
            Blur.fastGaussian(m, w, rows, adjSigma, bl)
            val a = adjAmount
            val start = outStart * w
            val end = (outStart + outRows) * w
            if (mono) {
                for (i in start until end) r[i] += a * (m[i] - bl[i])
            } else {
                for (i in start until end) {
                    val d = a * (m[i] - bl[i])
                    r[i] += d; g[i] += d; b[i] += d
                }
            }
        }

        // C. 粒子（出力行のみ）
        val gy0 = absY0 + outStart
        val n = w * outRows
        var nShared: FloatArray? = null
        var nInd: Array<FloatArray>? = null
        if (grainAmp > 0f) {
            val fine = FloatArray(n)
            grainField.generate(0, gy0, w, outRows, grainSigma, 0, fine)
            val coarse = FloatArray(n)
            grainField.generate(0, gy0, w, outRows, grainSigma * 2.2f, 1, coarse)
            val inv = 1f / sqrt(1f + 0.5f * 0.5f)
            for (i in 0 until n) fine[i] = (fine[i] + 0.5f * coarse[i]) * inv
            nShared = fine
            if (!mono && grainChroma > 0f) {
                nInd = Array(3) { c ->
                    val f = FloatArray(n)
                    grainField.generate(0, gy0, w, outRows, grainSigma * layerSize[c], 2 + c, f)
                    f
                }
            }
        }

        // D. プリント → 表示
        val sA = sqrt(1f - grainChroma)
        val sB = sqrt(grainChroma)
        Parallel.forChunks(outRows, 8) { s, e ->
            for (yy in s until e) {
                val y = gy0 + yy
                val wrow = (outStart + yy) * w
                val orow = outOffset + yy * w * 3
                for (x in 0 until w) {
                    val i = wrow + x
                    val gi = yy * w + x
                    var t0: Float
                    var t1: Float
                    var t2: Float
                    if (mono) {
                        var d = r[i]
                        if (nShared != null) d += grainSigmaD(d, dMidF[0], grainAmp) * nShared[gi]
                        val dp = printLut!![0].eval(dMidF[0] - d)
                        val t = transLut.eval(dp - baseD)
                        t0 = t; t1 = t; t2 = t
                    } else {
                        var d0 = r[i]
                        var d1 = g[i]
                        var d2 = b[i]
                        if (nShared != null) {
                            val ns = nShared[gi]
                            val n0 = if (nInd != null) sA * ns + sB * nInd[0][gi] else ns
                            val n1 = if (nInd != null) sA * ns + sB * nInd[1][gi] else ns
                            val n2 = if (nInd != null) sA * ns + sB * nInd[2][gi] else ns
                            d0 += grainSigmaD(d0, dMidF[0], grainAmp * layerAmp[0]) * n0
                            d1 += grainSigmaD(d1, dMidF[1], grainAmp * layerAmp[1]) * n1
                            d2 += grainSigmaD(d2, dMidF[2], grainAmp * layerAmp[2]) * n2
                        }
                        val p0: Float
                        val p1: Float
                        val p2: Float
                        if (printLut != null) {
                            p0 = printLut[0].eval(dMidF[0] - d0 + offF[0])
                            p1 = printLut[1].eval(dMidF[1] - d1 + offF[1])
                            p2 = printLut[2].eval(dMidF[2] - d2 + offF[2])
                        } else {
                            p0 = d0; p1 = d1; p2 = d2
                        }
                        val v0 = dye[0] * p0 + dye[1] * p1 + dye[2] * p2
                        val v1 = dye[3] * p0 + dye[4] * p1 + dye[5] * p2
                        val v2 = dye[6] * p0 + dye[7] * p1 + dye[8] * p2
                        t0 = transLut.eval(v0 - baseD)
                        t1 = transLut.eval(v1 - baseD)
                        t2 = transLut.eval(v2 - baseD)
                    }
                    // Rec.2020 → Display P3
                    var o0 = toP3[0] * t0 + toP3[1] * t1 + toP3[2] * t2
                    var o1 = toP3[3] * t0 + toP3[4] * t1 + toP3[5] * t2
                    var o2 = toP3[6] * t0 + toP3[7] * t1 + toP3[8] * t2
                    // ガマットマッピング（輝度を保って彩度を落とす）
                    if (o0 < 0f || o1 < 0f || o2 < 0f || o0 > 1f || o1 > 1f || o2 > 1f) {
                        val yl = 0.2289746f * o0 + 0.6917385f * o1 + 0.0792869f * o2
                        if (yl >= 1f) {
                            o0 = 1f; o1 = 1f; o2 = 1f
                        } else if (yl <= 0f) {
                            o0 = 0f; o1 = 0f; o2 = 0f
                        } else {
                            var t = 1f
                            t = gamutT(o0, yl, t); t = gamutT(o1, yl, t); t = gamutT(o2, yl, t)
                            o0 = yl + (o0 - yl) * t
                            o1 = yl + (o1 - yl) * t
                            o2 = yl + (o2 - yl) * t
                        }
                    }
                    // 8bit 化（TPDF ディザ）
                    val dz = dither(x, y)
                    out[orow + x * 3] = quant(oetf.eval(o0) + dz)
                    out[orow + x * 3 + 1] = quant(oetf.eval(o1) + dither(x + 7919, y))
                    out[orow + x * 3 + 2] = quant(oetf.eval(o2) + dither(x, y + 7919))
                }
            }
        }
    }

    private fun gamutT(o: Float, yl: Float, t: Float): Float {
        if (o < 0f) return min(t, yl / (yl - o))
        if (o > 1f) return min(t, (1f - yl) / (o - yl))
        return t
    }

    private fun grainSigmaD(d: Float, dMid: Float, amp: Float): Float {
        val rel = if (dMid > 1e-4f) (d / dMid) else 1f
        val c = if (rel < 0f) 0f else if (rel > 4f) 4f else rel
        return amp * (0.35f + 0.65f * sqrt(c))
    }

    private fun dither(x: Int, y: Int): Float {
        var h = x * 0x27d4eb2d xor (y * 0x165667b1)
        h = (h xor (h ushr 15)) * 0x2c1b3c6d
        h = (h xor (h ushr 12)) * 0x297a2d39
        h = h xor (h ushr 15)
        val u1 = (h and 0xFFFF) / 65535f
        val u2 = ((h ushr 16) and 0xFFFF) / 65535f
        return u1 + u2 - 1f
    }

    private fun quant(v: Float): Byte {
        val i = (v + 0.5f).toInt()
        return (if (i < 0) 0 else if (i > 255) 255 else i).toByte()
    }

    // ---- 自動露出用: 無彩色のシーン輝度 → 表示輝度 ----

    fun neutralDisplayY(sceneY: Double): Double {
        val e = sceneY.coerceAtLeast(1e-9)
        val u = log10(e / 0.18)
        if (mono) {
            val d = density(0, u)
            val dp = printLut!![0].eval((dMid[0] - d).toFloat())
            return 10.0.pow(-(dp - baseD).toDouble())
        }
        val d = DoubleArray(3) { density(it, u) }
        val dd = DoubleArray(3) { i -> dirM[i * 3] * d[0] + dirM[i * 3 + 1] * d[1] + dirM[i * 3 + 2] * d[2] }
        val p = DoubleArray(3) { c ->
            if (printLut != null) printLut[c].eval((dMid[c] - dd[c] + offF[c]).toFloat()).toDouble() else dd[c]
        }
        val t = DoubleArray(3) { i -> 10.0.pow(-(dye[i * 3] * p[0] + dye[i * 3 + 1] * p[1] + dye[i * 3 + 2] * p[2] - baseD)) }
        return 0.2627 * t[0] + 0.6780 * t[1] + 0.0593 * t[2]
    }

    /** 表示輝度 target を得るためのシーン輝度（二分探索） */
    fun sceneForDisplayY(target: Double): Double {
        var lo = -24.0
        var hi = 8.0
        val tg = target.coerceIn(1e-5, 0.97)
        repeat(60) {
            val mid = (lo + hi) / 2
            if (neutralDisplayY(2.0.pow(mid)) < tg) lo = mid else hi = mid
        }
        return 2.0.pow((lo + hi) / 2)
    }
}
