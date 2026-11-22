package filmlab.core.cli

import filmlab.core.FilmLab
import filmlab.core.film.DevelopParams
import filmlab.core.film.FilmStock
import filmlab.core.raw.ReferenceStats
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * PC 上でのフィルム現像（動作確認・チューニング用）
 *   gradle :core:run --args="input.dng out.jpg --stock nega400 --ev 0.3"
 * オプション: --stock ID, --ev X, --grain X, --halation X, --preview N（縮小のみ）, --all（全銘柄のプレビュー）,
 *             --no-ae, --neutral（フィルム処理なしの比較画像）, --info
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("usage: <input.dng> [output.jpg] [--stock ${FilmStock.ALL.joinToString("|") { it.id }}] [--ev X] [--grain X] [--halation X] [--preview N] [--all] [--no-ae] [--neutral] [--info]")
        exitProcess(1)
    }
    val input = File(args[0])
    var output: File? = null
    var params = DevelopParams()
    var preview = 0
    var all = false
    var neutral = false
    var infoOnly = false
    var i = 1
    while (i < args.size) {
        when (val a = args[i]) {
            "--stock" -> params = params.copy(stockId = args[++i])
            "--ev" -> params = params.copy(exposureEv = args[++i].toFloat())
            "--grain" -> params = params.copy(grain = args[++i].toFloat())
            "--grain-size" -> params = params.copy(grainSize = args[++i].toFloat())
            "--halation" -> params = params.copy(halation = args[++i].toFloat())
            "--sharpen" -> params = params.copy(sharpen = args[++i].toFloat())
            "--nr" -> params = params.copy(chromaNr = args[++i].toFloat())
            "--warmth" -> params = params.copy(warmth = args[++i].toFloat())
            "--tint" -> params = params.copy(tint = args[++i].toFloat())
            "--contrast" -> params = params.copy(contrast = args[++i].toFloat())
            "--saturation" -> params = params.copy(saturation = args[++i].toFloat())
            "--camera-wb" -> params = params.copy(filmWhiteBalance = false)
            "--no-ae" -> params = params.copy(autoExposure = false)
            "--preview" -> preview = args[++i].toInt()
            "--all" -> all = true
            "--neutral" -> neutral = true
            "--info" -> infoOnly = true
            else -> if (output == null && !a.startsWith("--")) output = File(a) else error("unknown option $a")
        }
        i++
    }

    val t0 = System.nanoTime()
    val buf = RandomAccessFile(input, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }
    val session = FilmLab.open(buf)
    val t1 = System.nanoTime()
    print(session.describe())
    println("デコード: %.2fs".format((t1 - t0) / 1e9))
    if (infoOnly) return

    val ref = session.dng.previewJpeg()?.let { bytes ->
        try {
            val img = ImageIO.read(ByteArrayInputStream(bytes))
            val px = IntArray(img.width * img.height)
            img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
            println("埋め込みプレビュー: ${img.width}x${img.height}")
            ReferenceStats.fromArgb(px)
        } catch (e: Exception) {
            println("プレビューの読み込みに失敗: $e")
            null
        }
    }
    ref?.let { println("参照輝度(中央値): %.4f".format(it.medianLuminance)) }
    val base = output ?: File(input.parentFile, input.nameWithoutExtension + "_film.jpg")

    val stocks = if (all) FilmStock.ALL else listOf(FilmStock.byId(params.stockId))
    for (st in stocks) {
        val p = params.copy(stockId = st.id)
        val ta = System.nanoTime()
        val analysis = session.analyze(p, ref)
        println("[${st.id}] ${analysis.note} 自動補正 %+.2f EV, ゲイン %.3f, 解析 %.2fs".format(analysis.autoGainEv, analysis.gain, (System.nanoTime() - ta) / 1e9))
        val out = if (stocks.size > 1) File(base.parentFile, base.nameWithoutExtension + "_" + st.id + ".jpg") else base
        if (preview > 0 || all) {
            val n = if (preview > 0) preview else 1600
            val img = if (neutral) session.renderNeutralPreview(p, analysis, n) else session.renderPreview(p, analysis, n)
            BufferedOutputStream(FileOutputStream(out)).use { FilmLab.previewToJpeg(img, it) }
            println("プレビュー ${img.width}x${img.height} → $out")
        } else {
            val tr = System.nanoTime()
            BufferedOutputStream(FileOutputStream(out), 1 shl 20).use { os ->
                FilmLab.exportJpeg(session, p, analysis, os)
            }
            println("フル解像度 ${session.outW}x${session.outH} → $out (%.2fs, %.1f MB)".format((System.nanoTime() - tr) / 1e9, out.length() / 1e6))
        }
    }
}
