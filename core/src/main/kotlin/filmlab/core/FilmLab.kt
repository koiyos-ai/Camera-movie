package filmlab.core

import filmlab.core.dng.DngFile
import filmlab.core.film.DevelopParams
import filmlab.core.output.ExifWriter
import filmlab.core.output.IccProfile
import filmlab.core.output.JpegEncoder
import filmlab.core.raw.Analysis
import filmlab.core.raw.DevelopSession
import filmlab.core.raw.PreviewImage
import java.io.OutputStream
import java.nio.ByteBuffer

/** アプリ / CLI から使う窓口 */
object FilmLab {
    const val SOFTWARE = "FilmLab"

    /** DNG を開いて RAW をデコードする（重い処理） */
    fun open(buffer: ByteBuffer): DevelopSession {
        val dng = DngFile(buffer)
        val raw = dng.decodeRaw()
        return DevelopSession(dng, raw)
    }

    /** フル解像度でフィルム現像し、EXIF と Display P3 プロファイル付き JPEG(4:4:4) を書き出す */
    fun exportJpeg(
        session: DevelopSession, params: DevelopParams, analysis: Analysis, out: OutputStream,
        progress: ((Float) -> Unit)? = null,
    ) {
        val exif = try {
            ExifWriter.build(session.dng, session.outW, session.outH, SOFTWARE)
        } catch (e: Exception) {
            null
        }
        val enc = JpegEncoder(out, session.outW, session.outH, params.jpegQuality, exif, IccProfile.displayP3)
        session.renderFull(params, analysis, enc, progress)
        enc.finish()
    }

    /** プレビュー画像を JPEG にする（CLI 用） */
    fun previewToJpeg(p: PreviewImage, out: OutputStream, quality: Int = 92) {
        val enc = JpegEncoder(out, p.width, p.height, quality, null, IccProfile.displayP3)
        val row = ByteArray(p.width * 3)
        for (y in 0 until p.height) {
            for (x in 0 until p.width) {
                val c = p.argb[y * p.width + x]
                row[x * 3] = (c shr 16).toByte()
                row[x * 3 + 1] = (c shr 8).toByte()
                row[x * 3 + 2] = c.toByte()
            }
            enc.writeRows(row, 1)
        }
        enc.finish()
    }
}
