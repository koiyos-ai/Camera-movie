package filmlab.core.film

import filmlab.core.util.Mat3

enum class FilmProcess { NEGATIVE, REVERSAL, BW }

/**
 * フィルム銘柄（の性格）を表すパラメータ。実在フィルムの測定値ではなく、
 * 「ネガ → プリント」の物理的な仕組みを簡略化したモデルの係数として設計している。
 *
 * 濃度は log10 単位。露光量 u は「中間グレー(18%)を 0 とした log10 露光量」。
 */
class FilmStock(
    val id: String,
    val label: String,
    val description: String,
    val process: FilmProcess,
    /** シーンの RGB → 各感光層への露光（分光感度の重なり）。各行の和は 1 に正規化される */
    sensitivity: Mat3,
    /** 特性曲線の傾き（ガンマ）R,G,B 層 */
    val gamma: DoubleArray,
    /** トウ（暗部の肩）の位置 u */
    val toe: DoubleArray,
    /** ショルダー（明部の肩）の位置 u */
    val shoulder: DoubleArray,
    val toeSoftness: Double,
    val shoulderSoftness: Double,
    /** 層間抑制効果（DIR カプラー）の強さ。彩度と色の分離感が上がる */
    val interlayer: Double,
    /** 隣接効果（現像のエッジ効果）の強さと半径（長辺に対する比） */
    val adjacency: Double,
    val adjacencyRadius: Double,
    /** プリント（印画紙/ポジ）側。ネガ系のみ */
    val printGamma: DoubleArray = doubleArrayOf(2.0, 2.0, 2.0),
    val printDmin: Double = 0.06,
    val printDmax: Double = 2.15,
    val printMid: Double = 0.74,
    /** 焼き付け時のカラータイミング（各層の露光 log10 オフセット。+で該当色が減る） */
    val printerOffset: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0),
    /** 色素の副吸収（濃度の混ざり）。各行の和は 1 に正規化される */
    dye: Mat3 = Mat3.IDENTITY,
    /** 粒子の大きさ（35mm 判換算の µm）と量（中間調の濃度 RMS） */
    val grainSizeUm: Double,
    val grainAmount: Double,
    /** 0 = 完全モノクロ粒子, 1 = 層ごとに独立（カラー粒子） */
    val grainChroma: Double,
    /** ハレーション（ベース面反射による赤いにじみ）の強さ、半径（長辺比）、しきい値（シーン線形） */
    val halation: Double,
    val halationRadius: Double,
    val halationThreshold: Double,
    /** フィルム固有のホワイトバランス（K）。null ならカメラの AWB（ラボでの補正済みプリント相当） */
    val kelvin: Double? = null,
    /** モノクロのパンクロ感度（Rec.2020 線形 RGB の重み） */
    val bwWeights: DoubleArray = doubleArrayOf(0.30, 0.59, 0.11),
    /** 自動露出の補正（EV） */
    val exposureBias: Double = 0.0,
) {
    val sensitivity: Mat3 = sensitivity.normalizeRows()
    val dye: Mat3 = dye.normalizeRows()

    companion object {
        val NEGA_400 = FilmStock(
            id = "nega400",
            label = "カラーネガ 400 ナチュラル",
            description = "広いラチチュード、柔らかい階調と自然な肌色。中庸な粒状感。",
            process = FilmProcess.NEGATIVE,
            sensitivity = Mat3.of(0.90, 0.09, 0.01, 0.05, 0.88, 0.07, 0.01, 0.09, 0.90),
            gamma = doubleArrayOf(0.60, 0.62, 0.64),
            toe = doubleArrayOf(-1.60, -1.65, -1.70),
            shoulder = doubleArrayOf(2.20, 2.20, 2.10),
            toeSoftness = 0.28, shoulderSoftness = 0.35,
            interlayer = 0.10, adjacency = 0.10, adjacencyRadius = 0.0010,
            printGamma = doubleArrayOf(1.95, 2.00, 2.05), printDmin = 0.06, printDmax = 2.15, printMid = 0.74,
            printerOffset = doubleArrayOf(-0.010, 0.0, 0.015),
            dye = Mat3.of(1.0, 0.06, 0.02, 0.05, 1.0, 0.07, 0.02, 0.10, 1.0),
            grainSizeUm = 13.0, grainAmount = 0.010, grainChroma = 0.30,
            halation = 0.10, halationRadius = 0.006, halationThreshold = 1.0,
        )

        val NEGA_200 = FilmStock(
            id = "nega200",
            label = "カラーネガ 200 ウォーム",
            description = "暖色寄りで彩度が高め。晴天の屋外向き。粒子は細かめ。",
            process = FilmProcess.NEGATIVE,
            sensitivity = Mat3.of(0.94, 0.05, 0.01, 0.04, 0.91, 0.05, 0.01, 0.06, 0.93),
            gamma = doubleArrayOf(0.66, 0.66, 0.70),
            toe = doubleArrayOf(-1.45, -1.45, -1.50),
            shoulder = doubleArrayOf(2.00, 2.00, 1.95),
            toeSoftness = 0.26, shoulderSoftness = 0.33,
            interlayer = 0.22, adjacency = 0.12, adjacencyRadius = 0.0010,
            printGamma = doubleArrayOf(2.10, 2.10, 2.15), printDmin = 0.06, printDmax = 2.20, printMid = 0.72,
            printerOffset = doubleArrayOf(-0.025, 0.0, 0.035),
            dye = Mat3.of(1.0, 0.05, 0.02, 0.05, 1.0, 0.06, 0.02, 0.09, 1.0),
            grainSizeUm = 12.0, grainAmount = 0.009, grainChroma = 0.30,
            halation = 0.12, halationRadius = 0.006, halationThreshold = 1.0,
        )

        val CINE_500T = FilmStock(
            id = "cine500t",
            label = "シネマ 500T タングステン",
            description = "3200K バランスの映画用ネガ。昼光では青く、光源の周りに赤いハレーション。",
            process = FilmProcess.NEGATIVE,
            sensitivity = Mat3.of(0.89, 0.10, 0.01, 0.06, 0.86, 0.08, 0.01, 0.10, 0.89),
            gamma = doubleArrayOf(0.58, 0.58, 0.60),
            toe = doubleArrayOf(-1.80, -1.80, -1.85),
            shoulder = doubleArrayOf(2.40, 2.40, 2.35),
            toeSoftness = 0.30, shoulderSoftness = 0.38,
            interlayer = 0.08, adjacency = 0.08, adjacencyRadius = 0.0010,
            printGamma = doubleArrayOf(2.35, 2.35, 2.40), printDmin = 0.05, printDmax = 2.60, printMid = 0.80,
            printerOffset = doubleArrayOf(0.0, 0.0, 0.0),
            dye = Mat3.of(1.0, 0.07, 0.02, 0.06, 1.0, 0.07, 0.02, 0.11, 1.0),
            grainSizeUm = 16.0, grainAmount = 0.012, grainChroma = 0.35,
            halation = 0.55, halationRadius = 0.008, halationThreshold = 0.7,
            kelvin = 3200.0,
        )

        val REVERSAL_100 = FilmStock(
            id = "reversal100",
            label = "リバーサル 100 ビビッド",
            description = "スライドフィルム。高コントラスト・高彩度で黒が締まる。ハイライトの許容量は狭い。",
            process = FilmProcess.REVERSAL,
            sensitivity = Mat3.of(0.955, 0.04, 0.005, 0.03, 0.94, 0.03, 0.005, 0.04, 0.955),
            gamma = doubleArrayOf(1.55, 1.55, 1.60),
            toe = doubleArrayOf(-1.05, -1.05, -1.00),
            shoulder = doubleArrayOf(0.62, 0.62, 0.64),
            toeSoftness = 0.22, shoulderSoftness = 0.22,
            interlayer = 0.26, adjacency = 0.10, adjacencyRadius = 0.0010,
            dye = Mat3.of(1.0, 0.05, 0.01, 0.04, 1.0, 0.05, 0.01, 0.07, 1.0),
            grainSizeUm = 9.0, grainAmount = 0.006, grainChroma = 0.25,
            halation = 0.06, halationRadius = 0.005, halationThreshold = 1.2,
            exposureBias = -0.3,
        )

        val MONO_400 = FilmStock(
            id = "mono400",
            label = "モノクロ 400 ハイコントラスト",
            description = "パンクロのモノクロネガ。粗めの粒子とエッジの効いた描写。",
            process = FilmProcess.BW,
            sensitivity = Mat3.IDENTITY,
            gamma = doubleArrayOf(0.70, 0.70, 0.70),
            toe = doubleArrayOf(-1.50, -1.50, -1.50),
            shoulder = doubleArrayOf(2.30, 2.30, 2.30),
            toeSoftness = 0.26, shoulderSoftness = 0.35,
            interlayer = 0.0, adjacency = 0.22, adjacencyRadius = 0.0012,
            printGamma = doubleArrayOf(2.20, 2.20, 2.20), printDmin = 0.05, printDmax = 2.10, printMid = 0.75,
            grainSizeUm = 20.0, grainAmount = 0.016, grainChroma = 0.0,
            halation = 0.05, halationRadius = 0.005, halationThreshold = 1.2,
            bwWeights = doubleArrayOf(0.33, 0.56, 0.11),
        )

        val ALL: List<FilmStock> = listOf(NEGA_400, NEGA_200, CINE_500T, REVERSAL_100, MONO_400)

        fun byId(id: String?): FilmStock = ALL.firstOrNull { it.id == id } ?: NEGA_400
    }
}

/** ユーザーが調整する現像パラメータ（UI の値の範囲はコメント参照） */
data class DevelopParams(
    val stockId: String = FilmStock.NEGA_400.id,
    /** 露出補正 EV（-3..3） */
    val exposureEv: Float = 0f,
    /** 自動露出（Pixel の処理済みプレビューの明るさに合わせる） */
    val autoExposure: Boolean = true,
    /** フィルム固有の WB を使う（シネマ 500T の 3200K など）。false ならカメラ AWB */
    val filmWhiteBalance: Boolean = true,
    /** 色温度（-1..1、+で暖色）とティント（-1..1、+でマゼンタ） */
    val warmth: Float = 0f,
    val tint: Float = 0f,
    /** 粒状の量（0..2）と大きさ（0.5..2）の倍率 */
    val grain: Float = 1f,
    val grainSize: Float = 1f,
    /** ハレーションの倍率（0..3） */
    val halation: Float = 1f,
    /** コントラスト・彩度（-1..1） */
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    /** RAW のシャープ化（0..2）と色ノイズ低減（0..2） */
    val sharpen: Float = 0.6f,
    val chromaNr: Float = 0.6f,
    /** JPEG 品質（70..100） */
    val jpegQuality: Int = 95,
) {
    fun toMap(): Map<String, String> = mapOf(
        "stockId" to stockId,
        "exposureEv" to exposureEv.toString(),
        "autoExposure" to autoExposure.toString(),
        "filmWhiteBalance" to filmWhiteBalance.toString(),
        "warmth" to warmth.toString(),
        "tint" to tint.toString(),
        "grain" to grain.toString(),
        "grainSize" to grainSize.toString(),
        "halation" to halation.toString(),
        "contrast" to contrast.toString(),
        "saturation" to saturation.toString(),
        "sharpen" to sharpen.toString(),
        "chromaNr" to chromaNr.toString(),
        "jpegQuality" to jpegQuality.toString(),
    )

    companion object {
        fun fromMap(m: Map<String, String?>): DevelopParams {
            val d = DevelopParams()
            fun f(k: String, def: Float) = m[k]?.toFloatOrNull() ?: def
            fun b(k: String, def: Boolean) = m[k]?.toBooleanStrictOrNull() ?: def
            return DevelopParams(
                stockId = m["stockId"] ?: d.stockId,
                exposureEv = f("exposureEv", d.exposureEv),
                autoExposure = b("autoExposure", d.autoExposure),
                filmWhiteBalance = b("filmWhiteBalance", d.filmWhiteBalance),
                warmth = f("warmth", d.warmth),
                tint = f("tint", d.tint),
                grain = f("grain", d.grain),
                grainSize = f("grainSize", d.grainSize),
                halation = f("halation", d.halation),
                contrast = f("contrast", d.contrast),
                saturation = f("saturation", d.saturation),
                sharpen = f("sharpen", d.sharpen),
                chromaNr = f("chromaNr", d.chromaNr),
                jpegQuality = m["jpegQuality"]?.toIntOrNull() ?: d.jpegQuality,
            )
        }
    }
}
