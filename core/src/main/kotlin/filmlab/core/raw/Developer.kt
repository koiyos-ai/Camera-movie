package filmlab.core.raw

import filmlab.core.dng.DngFile
import filmlab.core.dng.FixVignetteRadialOp
import filmlab.core.dng.GainMapOp
import filmlab.core.dng.MapPolynomialOp
import filmlab.core.dng.MapTableOp
import filmlab.core.dng.Opcode
import filmlab.core.dng.Opcodes
import filmlab.core.dng.PerRowColOp
import filmlab.core.dng.RawImage
import filmlab.core.dng.WarpRectilinearOp
import filmlab.core.film.DevelopParams
import filmlab.core.film.FilmRenderer
import filmlab.core.film.FilmStock
import filmlab.core.film.HalationField
import filmlab.core.util.Blur
import filmlab.core.util.FastMath
import filmlab.core.util.Mat3
import filmlab.core.util.Parallel
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Pixel が HDR+ 処理した埋め込みプレビューの明るさ（自動露出の目標）。線形輝度の中央値。 */
class ReferenceStats(val medianLuminance: Float) {
    companion object {
        /** sRGB/P3 エンコードの 8bit ARGB 画素から作る */
        fun fromArgb(pixels: IntArray): ReferenceStats? {
            if (pixels.isEmpty()) return null
            val lut = FloatArray(256) { ColorMath.srgbEotf(it / 255.0).toFloat() }
            val hist = IntArray(4096)
            for (p in pixels) {
                val y = 0.2126f * lut[(p shr 16) and 0xFF] + 0.7152f * lut[(p shr 8) and 0xFF] + 0.0722f * lut[p and 0xFF]
                hist[(y * 4095f).toInt().coerceIn(0, 4095)]++
            }
            var acc = 0
            val half = pixels.size / 2
            for (i in hist.indices) {
                acc += hist[i]
                if (acc >= half) return ReferenceStats((i + 0.5f) / 4095f)
            }
            return null
        }
    }
}

/** RGB 8bit の行を受け取る出力先（JPEG エンコーダなど） */
fun interface RowSink {
    /** rgb: 行数 rows × width × 3 バイト（R,G,B の順） */
    fun writeRows(rgb: ByteArray, rows: Int)
}

/** 現像結果の縮小画像（プレビュー用） */
class PreviewImage(val width: Int, val height: Int, val argb: IntArray)

/** 出力ジオメトリ（DefaultCrop 後）でのカメラ RGB（WB 前）縮小画像 */
class Proxy(val w: Int, val h: Int, val r: FloatArray, val g: FloatArray, val b: FloatArray)

/** 解析結果: 露出ゲインとハレーション（プレビューとフル解像度で共通に使う） */
class Analysis(
    val gain: Float,
    val autoGainEv: Double,
    val halation: FloatArray?,
    val halW: Int,
    val halH: Int,
    val note: String,
)

/**
 * 1 枚の DNG の現像セッション。デコード済み RAW を保持し、プレビュー（縮小）と
 * フル解像度（帯ごとに処理してストリーミング出力）を同じパラメータで描画する。
 */
class DevelopSession(val dng: DngFile, val raw: RawImage) {
    val meta = dng.meta
    val color = DngColor(meta)
    private val imgW = raw.width
    private val imgH = raw.height

    private val warpOp: WarpRectilinearOp? = meta.opcodes3.filterIsInstance<WarpRectilinearOp>().firstOrNull { !it.isIdentity() }
    private val warp: WarpMapper? = warpOp?.let { WarpMapper(it, imgW, imgH) }

    /** DefaultCrop。歪曲補正で RAW の外を参照する縁があれば、その分だけ内側に詰める */
    private val crop: IntArray = computeCrop()
    val outW = crop[2]
    val outH = crop[3]
    private val cropX = crop[0]
    private val cropY = crop[1]
    /** 歪曲補正のために詰めた画素数（上, 下, 左, 右） */
    val warpInset: IntArray = crop.copyOfRange(4, 8)
    private val preWarpOps: List<Opcode>
    private val postWarpOps: List<Opcode>

    init {
        val list = meta.opcodes3
        val wi = if (warpOp == null) list.size else list.indexOf(warpOp)
        fun pointOp(o: Opcode) = o is GainMapOp || o is FixVignetteRadialOp || o is MapPolynomialOp || o is MapTableOp || o is PerRowColOp
        preWarpOps = list.subList(0, wi).filter(::pointOp)
        postWarpOps = if (wi < list.size) list.subList(wi + 1, list.size).filter(::pointOp) else emptyList()
    }

    /** 粒子の乱数シード（画像ごとに変える） */
    val seed: Long = run {
        var h = 1469598103934665603L
        val d = raw.data[0]
        var i = 0
        while (i < d.size) {
            h = (h xor java.lang.Float.floatToRawIntBits(d[i]).toLong()) * 1099511628211L
            i += 9973
        }
        h
    }

    private val demosaic: Demosaic? = raw.cfa?.let { Demosaic(raw.data[0], imgW, imgH, it) }

    fun describe(): String = meta.describe() +
        "出力: ${outW}x$outH  歪曲補正: ${if (warp != null) "あり(${warpOp!!.planes}plane) 縁の切り詰め ${warpInset.joinToString()}" else "なし"}\n"

    private fun computeCrop(): IntArray {
        var x0 = meta.defaultCropX
        var y0 = meta.defaultCropY
        var x1 = x0 + meta.defaultCropW - 1
        var y1 = y0 + meta.defaultCropH - 1
        val wp = warp ?: return intArrayOf(x0, y0, x1 - x0 + 1, y1 - y0 + 1, 0, 0, 0, 0)
        val t = DoubleArray(2)
        fun inside(x: Int, y: Int): Boolean {
            for (pl in 0 until 3) {
                wp.map(x.toDouble(), y.toDouble(), pl, t)
                if (t[0] < 0.0 || t[1] < 0.0 || t[0] > imgW - 1.0 || t[1] > imgH - 1.0) return false
            }
            return true
        }
        fun rowOk(y: Int, xa: Int, xb: Int): Boolean {
            var x = xa
            while (x < xb) {
                if (!inside(x, y)) return false
                x += 8
            }
            return inside(xb, y)
        }
        fun colOk(x: Int, ya: Int, yb: Int): Boolean {
            var y = ya
            while (y < yb) {
                if (!inside(x, y)) return false
                y += 8
            }
            return inside(x, yb)
        }
        val limX = meta.defaultCropW * 15 / 100
        val limY = meta.defaultCropH * 15 / 100
        val ox0 = x0
        val oy0 = y0
        val ox1 = x1
        val oy1 = y1
        // 4 辺を 1 画素ずつ順番に内側へ（角の外れを片方の辺だけで吸収しないように）
        while (true) {
            var moved = false
            if (y0 - oy0 < limY && !rowOk(y0, x0, x1)) { y0++; moved = true }
            if (oy1 - y1 < limY && !rowOk(y1, x0, x1)) { y1--; moved = true }
            if (x0 - ox0 < limX && !colOk(x0, y0, y1)) { x0++; moved = true }
            if (ox1 - x1 < limX && !colOk(x1, y0, y1)) { x1--; moved = true }
            if (!moved) break
        }
        return intArrayOf(x0, y0, x1 - x0 + 1, y1 - y0 + 1, y0 - oy0, oy1 - y1, x0 - ox0, ox1 - x1)
    }

    // ------------------------------------------------------------------
    // 色の設定
    // ------------------------------------------------------------------

    class ColorSetup(val neutral: DoubleArray, val camToWork: Mat3, val whiteXy: DoubleArray)

    fun colorSetup(params: DevelopParams): ColorSetup {
        val stock = FilmStock.byId(params.stockId)
        val baseNeutral = color.asShotNeutral()
        var white = color.neutralToXy(baseNeutral)
        if (params.filmWhiteBalance && stock.kelvin != null) white = ColorMath.xyFromCct(stock.kelvin)
        // +warmth で画像を暖色に = 想定光源をより青い側（ミレッドを下げる）へ
        white = ColorMath.shiftWhite(white, -60.0 * params.warmth, 0.012 * params.tint)
        val neutral = color.xyToNeutral(white)
        val camToXyz = color.cameraToXyzD50(neutral)
        val toWork = ColorMath.XYZ_TO_REC2020 * ColorMath.bradford(ColorMath.D50_XY, ColorMath.D65_XY) * camToXyz
        return ColorSetup(neutral, toWork, white)
    }

    /**
     * カメラ RGB → 作業色空間（Rec.2020 シーン線形）。ハイライトで一部のチャンネルだけが
     * 飽和した画素は、WB 後に色が付かないよう白へ寄せる（マゼンタ被り防止）。
     */
    private fun toWorking(r: FloatArray, g: FloatArray, b: FloatArray, n: Int, cs: ColorSetup, gain: Float) {
        val inv = floatArrayOf((1 / cs.neutral[0]).toFloat(), (1 / cs.neutral[1]).toFloat(), (1 / cs.neutral[2]).toFloat())
        val post = (cs.camToWork * Mat3.diag(cs.neutral)).scale(gain.toDouble()).toFloatArray()
        Parallel.forChunks(n, 4096) { s, e ->
            for (i in s until e) {
                val cr = r[i]
                val cg = g[i]
                val cb = b[i]
                var wr = cr * inv[0]
                var wg = cg * inv[1]
                var wb = cb * inv[2]
                val mx = max(cr, max(cg, cb))
                if (mx > 0.90f) {
                    val t = FastMath.smoothstep(0.90f, 1.0f, mx)
                    wr += (min(wr, 1f) - wr) * t
                    wg += (min(wg, 1f) - wg) * t
                    wb += (min(wb, 1f) - wb) * t
                }
                r[i] = post[0] * wr + post[1] * wg + post[2] * wb
                g[i] = post[3] * wr + post[4] * wg + post[5] * wb
                b[i] = post[6] * wr + post[7] * wg + post[8] * wb
            }
        }
    }

    // ------------------------------------------------------------------
    // 縮小画像（プレビュー・解析用）
    // ------------------------------------------------------------------

    private val proxyCache = HashMap<Int, Proxy>()

    fun proxy(longSide: Int): Proxy = synchronized(proxyCache) {
        proxyCache.getOrPut(longSide) { buildProxy(longSide) }
    }

    private fun buildProxy(longSide: Int): Proxy {
        val scale = max(outW, outH).toDouble() / longSide
        val pw = max(1, (outW / scale).roundToInt())
        val ph = max(1, (outH / scale).roundToInt())

        // 1-2. 2x2 スーパーピクセル（CFA）/ 2x2 平均（LinearRaw）を k×k 個ずつ箱型平均して縮小。
        //      半分解像度の中間配列は作らない（50MP でもメモリを圧迫しない）
        val hw = imgW / 2
        val hh = imgH / 2
        val k = max(1, floor(hw / (pw * 1.0)).toInt().coerceAtMost(floor(hh / (ph * 1.0)).toInt()))
        val dw = hw / k
        val dh = hh / k
        val dr = FloatArray(dw * dh)
        val dg = FloatArray(dw * dh)
        val db = FloatArray(dw * dh)
        val cfa = raw.cfa
        val inv = 1f / (k * k)
        Parallel.forChunks(dh, 8) { s, e ->
            for (j in s until e) for (i in 0 until dw) {
                var a = 0f
                var b2 = 0f
                var c = 0f
                for (sj in 0 until k) for (si in 0 until k) {
                    val x = (i * k + si) * 2
                    val y = (j * k + sj) * 2
                    if (cfa != null) {
                        val d = raw.data[0]
                        for (dy in 0..1) for (dx in 0..1) {
                            val v = d[(y + dy) * imgW + x + dx]
                            when (cfa.colorAt(x + dx, y + dy)) {
                                0 -> a += v
                                1 -> b2 += v * 0.5f
                                else -> c += v
                            }
                        }
                    } else {
                        val o00 = y * imgW + x
                        val o10 = o00 + imgW
                        a += 0.25f * (raw.data[0][o00] + raw.data[0][o00 + 1] + raw.data[0][o10] + raw.data[0][o10 + 1])
                        b2 += 0.25f * (raw.data[1][o00] + raw.data[1][o00 + 1] + raw.data[1][o10] + raw.data[1][o10 + 1])
                        c += 0.25f * (raw.data[2][o00] + raw.data[2][o00 + 1] + raw.data[2][o10] + raw.data[2][o10 + 1])
                    }
                }
                val o = j * dw + i
                dr[o] = a * inv; dg[o] = b2 * inv; db[o] = c * inv
            }
        }
        // ステージ3座標 X → 縮小画像の座標: X = 2*(k*i + (k-1)/2) + 0.5
        fun toD(v: Double): Double = ((v - 0.5) / 2.0 - (k - 1) / 2.0) / k

        // 3. 歪曲補正前の opcode（GainMap など）を縮小画像に適用
        applyPointOpsAt(preWarpOps, dr, dg, db, dw, dh, xOf = { i -> 2.0 * (k * i + (k - 1) / 2.0) + 0.5 })

        // 4. 出力ジオメトリ（クロップ + 歪曲補正）でサンプリング
        val pr = FloatArray(pw * ph)
        val pg = FloatArray(pw * ph)
        val pb = FloatArray(pw * ph)
        val sx = outW.toDouble() / pw
        val sy = outH.toDouble() / ph
        Parallel.forChunks(ph, 8) { s, e ->
            val tmp = DoubleArray(2)
            for (j in s until e) for (i in 0 until pw) {
                val X = (i + 0.5) * sx - 0.5 + cropX
                val Y = (j + 0.5) * sy - 0.5 + cropY
                val o = j * pw + i
                if (warp == null) {
                    pr[o] = Resample.bilinear(dr, dw, dh, toD(X), toD(Y))
                    pg[o] = Resample.bilinear(dg, dw, dh, toD(X), toD(Y))
                    pb[o] = Resample.bilinear(db, dw, dh, toD(X), toD(Y))
                } else {
                    warp.map(X, Y, 0, tmp)
                    pr[o] = Resample.bilinear(dr, dw, dh, toD(tmp[0]), toD(tmp[1]))
                    if (warp.planes > 1) warp.map(X, Y, 1, tmp)
                    pg[o] = Resample.bilinear(dg, dw, dh, toD(tmp[0]), toD(tmp[1]))
                    if (warp.planes > 2) warp.map(X, Y, 2, tmp)
                    pb[o] = Resample.bilinear(db, dw, dh, toD(tmp[0]), toD(tmp[1]))
                }
            }
        }
        // 5. 歪曲補正後の opcode
        if (postWarpOps.isNotEmpty()) {
            applyPointOpsAt(postWarpOps, pr, pg, pb, pw, ph, { i -> (i + 0.5) * sx - 0.5 + cropX }, { j -> (j + 0.5) * sy - 0.5 + cropY })
        }
        return Proxy(pw, ph, pr, pg, pb)
    }

    /** 縮小画像の各画素を、ステージ3座標での位置に対する GainMap/周辺減光で補正 */
    private fun applyPointOpsAt(
        ops: List<Opcode>, r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int,
        xOf: (Int) -> Double, yOf: (Int) -> Double = xOf,
    ) {
        if (ops.isEmpty()) return
        val planes = arrayOf(r, g, b)
        Parallel.forChunks(h, 8) { s, e ->
            for (j in s until e) {
                val Y = yOf(j)
                for (i in 0 until w) {
                    val X = xOf(i)
                    for (op in ops) {
                        when (op) {
                            is GainMapOp -> {
                                for (p in op.area.plane until min(3, op.area.plane + op.area.planes)) {
                                    planes[p][j * w + i] *= gainAt(op, X, Y, p)
                                }
                            }
                            is FixVignetteRadialOp -> {
                                val cx = op.cx * imgW
                                val cy = op.cy * imgH
                                val m = Opcodes.maxCornerDistance(cx, cy, imgW.toDouble(), imgH.toDouble())
                                val r2 = ((X - cx) * (X - cx) + (Y - cy) * (Y - cy)) / (m * m)
                                val k = op.k
                                val gg = 1.0 + r2 * (k[0] + r2 * (k[1] + r2 * (k[2] + r2 * (k[3] + r2 * k[4]))))
                                for (p in 0..2) planes[p][j * w + i] = (planes[p][j * w + i] * gg).toFloat()
                            }
                            else -> {}
                        }
                    }
                }
            }
        }
    }

    private fun gainAt(op: GainMapOp, x: Double, y: Double, plane: Int): Float {
        val mp = min(plane, op.mapPlanes - 1)
        val rowF = (((y + 0.5) / imgH) - op.originV) / op.spacingV
        val colF = (((x + 0.5) / imgW) - op.originH) / op.spacingH
        val ri = rowF.coerceIn(0.0, (op.pointsV - 1).toDouble())
        val ci = colF.coerceIn(0.0, (op.pointsH - 1).toDouble())
        val r1 = ri.toInt()
        val c1 = ci.toInt()
        val r2 = min(r1 + 1, op.pointsV - 1)
        val c2 = min(c1 + 1, op.pointsH - 1)
        val rf = (ri - r1).toFloat()
        val cf = (ci - c1).toFloat()
        val a = op.gain(r1, c1, mp) + (op.gain(r1, c2, mp) - op.gain(r1, c1, mp)) * cf
        val b = op.gain(r2, c1, mp) + (op.gain(r2, c2, mp) - op.gain(r2, c1, mp)) * cf
        return a + (b - a) * rf
    }

    // ------------------------------------------------------------------
    // 解析（自動露出 + ハレーション）
    // ------------------------------------------------------------------

    fun analyze(params: DevelopParams, ref: ReferenceStats?, proxyLongSide: Int = 1600): Analysis {
        val stock = FilmStock.byId(params.stockId)
        val p = proxy(proxyLongSide)
        val cs = colorSetup(params)
        val n = p.w * p.h
        val r = p.r.copyOf()
        val g = p.g.copyOf()
        val b = p.b.copyOf()
        val base = 2.0.pow(meta.baselineExposure).toFloat()
        toWorking(r, g, b, n, cs, base)

        // 輝度の中央値（log2 ヒストグラム）
        val hist = IntArray(2048)
        for (i in 0 until n) {
            val y = 0.2627f * r[i] + 0.6780f * g[i] + 0.0593f * b[i]
            val l = FastMath.log2(max(y, 1e-9f))
            hist[((l + 24f) * 64f).toInt().coerceIn(0, 2047)]++
        }
        fun percentile(q: Double): Double {
            val target = (n * q).toInt()
            var acc = 0
            for (i in hist.indices) {
                acc += hist[i]
                if (acc > target) return 2.0.pow((i + 0.5) / 64.0 - 24.0)
            }
            return 0.18
        }
        val median = percentile(0.5)
        val renderer = FilmRenderer(stock, params, p.w, p.h, seed)
        var aeGain = 1.0
        var note: String
        if (params.autoExposure) {
            if (ref != null && ref.medianLuminance > 0.0005f) {
                val target = renderer.sceneForDisplayY(ref.medianLuminance.toDouble())
                aeGain = target / max(median, 1e-6)
                note = "自動露出: Pixel のプレビュー基準"
            } else {
                // 予備: 中央値を中間グレーの少し下へ
                aeGain = 0.12 / max(median, 1e-6)
                note = "自動露出: ヒストグラム基準"
            }
            aeGain = aeGain.coerceIn(2.0.pow(-4.0), 2.0.pow(6.0)) * 2.0.pow(stock.exposureBias)
        } else {
            note = "露出: DNG 基準（BaselineExposure ${"%.2f".format(meta.baselineExposure)} EV）"
        }
        val total = (base * aeGain * 2.0.pow(params.exposureEv.toDouble())).toFloat()
        val autoEv = ln2(aeGain)

        // ハレーション: 明るい部分（主に赤の成分）を広くぼかしたもの
        var hal: FloatArray? = null
        if (renderer.halationEnabled) {
            val k = total / base
            val thr = renderer.halationThreshold
            val src = FloatArray(n)
            var any = false
            for (i in 0 until n) {
                val l = (0.75f * r[i] + 0.25f * g[i]) * k
                val v = l - thr
                if (v > 0f) {
                    src[i] = v
                    any = true
                }
            }
            if (any) {
                val longSide = max(p.w, p.h)
                val s2 = (stock.halationRadius * longSide).toFloat()
                val s1 = s2 * 0.3f
                val a = FloatArray(n)
                val c = FloatArray(n)
                Blur.fastGaussian(src, p.w, p.h, s1, a)
                Blur.fastGaussian(src, p.w, p.h, s2, c)
                for (i in 0 until n) a[i] = 0.55f * a[i] + 0.45f * c[i]
                hal = a
            }
        }
        return Analysis(total, autoEv, hal, p.w, p.h, note)
    }

    private fun ln2(v: Double) = kotlin.math.ln(v) / kotlin.math.ln(2.0)

    // ------------------------------------------------------------------
    // プレビュー描画
    // ------------------------------------------------------------------

    fun renderPreview(params: DevelopParams, analysis: Analysis, longSide: Int = 1600): PreviewImage {
        val stock = FilmStock.byId(params.stockId)
        val p = proxy(longSide)
        val n = p.w * p.h
        val r = p.r.copyOf()
        val g = p.g.copyOf()
        val b = p.b.copyOf()
        toWorking(r, g, b, n, colorSetup(params), analysis.gain)
        val renderer = FilmRenderer(stock, params, p.w, p.h, seed)
        val hal = analysis.halation?.let { HalationField(it, analysis.halW, analysis.halH, p.w, p.h) }
        val out = ByteArray(n * 3)
        renderer.render(r, g, b, p.w, p.h, 0, 0, p.h, hal, out, 0)
        val argb = IntArray(n)
        for (i in 0 until n) {
            argb[i] = (0xFF shl 24) or ((out[i * 3].toInt() and 0xFF) shl 16) or
                ((out[i * 3 + 1].toInt() and 0xFF) shl 8) or (out[i * 3 + 2].toInt() and 0xFF)
        }
        return PreviewImage(p.w, p.h, argb)
    }

    /** フィルム処理なしの基準プレビュー（比較用: 同じ露出・WB での素直なトーン） */
    fun renderNeutralPreview(params: DevelopParams, analysis: Analysis, longSide: Int = 1600): PreviewImage {
        val p = proxy(longSide)
        val n = p.w * p.h
        val r = p.r.copyOf()
        val g = p.g.copyOf()
        val b = p.b.copyOf()
        toWorking(r, g, b, n, colorSetup(params), analysis.gain)
        val m = (ColorMath.XYZ_TO_P3 * ColorMath.REC2020_TO_XYZ).toFloatArray()
        val argb = IntArray(n)
        for (i in 0 until n) {
            fun tone(v: Float): Int {
                val x = max(0f, v)
                val t = x / (1f + x) * 1.18f // 簡易トーンマップ
                return (ColorMath.srgbOetf(min(1f, t).toDouble()) * 255 + 0.5).toInt().coerceIn(0, 255)
            }
            val o0 = m[0] * r[i] + m[1] * g[i] + m[2] * b[i]
            val o1 = m[3] * r[i] + m[4] * g[i] + m[5] * b[i]
            val o2 = m[6] * r[i] + m[7] * g[i] + m[8] * b[i]
            argb[i] = (0xFF shl 24) or (tone(o0) shl 16) or (tone(o1) shl 8) or tone(o2)
        }
        return PreviewImage(p.w, p.h, argb)
    }

    // ------------------------------------------------------------------
    // フル解像度描画（帯ごと）
    // ------------------------------------------------------------------

    /** 直近のフル現像の段階別所要時間（ns）: デモザイク等, 色変換, 色NR, シャープ, フィルム, 出力 */
    val stageNanos = LongArray(6)

    fun stageReport(): String {
        val names = arrayOf("デモザイク/レンズ補正", "色変換", "色ノイズ低減", "シャープ", "フィルム", "JPEG")
        return names.indices.joinToString("  ") { "%s %.2fs".format(names[it], stageNanos[it] / 1e9) }
    }

    fun renderFull(params: DevelopParams, analysis: Analysis, sink: RowSink, progress: ((Float) -> Unit)? = null) {
        stageNanos.fill(0)
        val stock = FilmStock.byId(params.stockId)
        val renderer = FilmRenderer(stock, params, outW, outH, seed)
        val cs = colorSetup(params)
        val hal = analysis.halation?.let { HalationField(it, analysis.halW, analysis.halH, outW, outH) }
        val margin = renderer.margin + Detail.NR_RADIUS + Detail.SHARPEN_RADIUS + 2
        val stripH = 128
        val w = outW
        val tmp = DoubleArray(2)
        val wx = FloatArray(4)
        val wy = FloatArray(4)
        var oy0 = 0
        while (oy0 < outH) {
            val oy1 = min(outH, oy0 + stripH)
            val wy0 = max(0, oy0 - margin)
            val wy1 = min(outH, oy1 + margin)
            val rows = wy1 - wy0
            val n = w * rows
            val r = FloatArray(n)
            val g = FloatArray(n)
            val b = FloatArray(n)
            var t = System.nanoTime()
            fun lap(i: Int) {
                val now = System.nanoTime()
                stageNanos[i] += now - t
                t = now
            }
            sampleCamera(wy0, wy1, r, g, b, tmp, wx, wy)
            lap(0)
            toWorking(r, g, b, n, cs, analysis.gain)
            lap(1)
            Detail.chromaDenoise(r, g, b, w, rows, params.chromaNr)
            lap(2)
            Detail.sharpen(r, g, b, w, rows, params.sharpen)
            lap(3)
            val out = ByteArray((oy1 - oy0) * w * 3)
            renderer.render(r, g, b, w, rows, wy0, oy0 - wy0, oy1 - oy0, hal, out, 0)
            lap(4)
            sink.writeRows(out, oy1 - oy0)
            lap(5)
            progress?.invoke(oy1.toFloat() / outH)
            oy0 = oy1
        }
    }

    /** 出力行 [wy0, wy1) のカメラ RGB（WB 前）を、デモザイク・レンズ補正・クロップ込みで取得 */
    private fun sampleCamera(
        wy0: Int, wy1: Int, r: FloatArray, g: FloatArray, b: FloatArray,
        tmp: DoubleArray, wx: FloatArray, wy: FloatArray,
    ) {
        val w = outW
        val rows = wy1 - wy0
        val wp = warp
        if (wp == null) {
            val sy0 = wy0 + cropY
            val sy1 = wy1 + cropY
            val sr = FloatArray(imgW * rows)
            val sg = FloatArray(imgW * rows)
            val sb = FloatArray(imgW * rows)
            sourceRows(sy0, sy1, sr, sg, sb)
            Opcodes.applyPointOps(preWarpOps + postWarpOps, arrayOf(sr, sg, sb), imgW, rows, sy0, 0, imgW, imgH, false)
            for (j in 0 until rows) {
                System.arraycopy(sr, j * imgW + cropX, r, j * w, w)
                System.arraycopy(sg, j * imgW + cropX, g, j * w, w)
                System.arraycopy(sb, j * imgW + cropX, b, j * w, w)
            }
            return
        }
        // 歪曲補正: 必要なソース行の範囲を求める
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        val probeYs = intArrayOf(wy0, wy1 - 1)
        for (py in probeYs) {
            var x = 0
            while (true) {
                for (pl in 0 until wp.planes) {
                    wp.map((x + cropX).toDouble(), (py + cropY).toDouble(), pl, tmp)
                    minY = min(minY, tmp[1]); maxY = max(maxY, tmp[1])
                }
                if (x == w - 1) break
                x = min(w - 1, x + 16)
            }
        }
        val sy0 = max(0, floor(minY).toInt() - 3)
        val sy1 = min(imgH, ceil(maxY).toInt() + 4).coerceAtLeast(sy0 + 1)
        val srows = sy1 - sy0
        val sr = FloatArray(imgW * srows)
        val sg = FloatArray(imgW * srows)
        val sb = FloatArray(imgW * srows)
        sourceRows(sy0, sy1, sr, sg, sb)
        Opcodes.applyPointOps(preWarpOps, arrayOf(sr, sg, sb), imgW, srows, sy0, 0, imgW, imgH, false)
        val planes = arrayOf(sr, sg, sb)
        val dst = arrayOf(r, g, b)
        Parallel.forChunks(rows, 4) { s, e ->
            val t = DoubleArray(2)
            val kx = FloatArray(4)
            val ky = FloatArray(4)
            for (j in s until e) {
                val Y = (wy0 + j + cropY).toDouble()
                for (i in 0 until w) {
                    val X = (i + cropX).toDouble()
                    for (pl in 0..2) {
                        if (pl == 0 || wp.planes > 1) wp.map(X, Y, pl, t)
                        dst[pl][j * w + i] = Resample.bicubic(planes[pl], imgW, srows, t[0], t[1] - sy0, kx, ky)
                    }
                }
            }
        }
        if (postWarpOps.isNotEmpty()) {
            Opcodes.applyPointOps(postWarpOps, dst, w, rows, wy0 + cropY, cropX, imgW, imgH, false)
        }
    }

    /** ステージ3（デモザイク後、ActiveArea 座標）の行 [y0, y1) を全幅で取得 */
    private fun sourceRows(y0: Int, y1: Int, r: FloatArray, g: FloatArray, b: FloatArray) {
        val dm = demosaic
        if (dm != null) {
            synchronized(dm) { dm.process(y0, y1, r, g, b) }
        } else {
            val n = (y1 - y0) * imgW
            System.arraycopy(raw.data[0], y0 * imgW, r, 0, n)
            System.arraycopy(raw.data[1], y0 * imgW, g, 0, n)
            System.arraycopy(raw.data[2], y0 * imgW, b, 0, n)
        }
    }

    companion object {
        fun luminanceStats(argb: IntArray): ReferenceStats? = ReferenceStats.fromArgb(argb)
        @Suppress("unused")
        private fun hypot(a: Double, b: Double) = sqrt(a * a + b * b)
    }
}
