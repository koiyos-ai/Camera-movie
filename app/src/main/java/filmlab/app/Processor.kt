package filmlab.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.net.Uri
import android.provider.MediaStore
import filmlab.core.FilmLab
import filmlab.core.film.DevelopParams
import filmlab.core.raw.Analysis
import filmlab.core.raw.DevelopSession
import filmlab.core.raw.PreviewImage
import filmlab.core.raw.ReferenceStats
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** 読み込み済みの DNG（現像セッション + Pixel の処理済みプレビュー） */
class LoadedDng(
    val uri: Uri,
    val name: String,
    val dateTakenMs: Long,
    val session: DevelopSession,
    val reference: ReferenceStats?,
    val pixelPreview: Bitmap?,
)

object Processor {
    const val PREVIEW_SIZE = 1600
    const val OUTPUT_DIR = "Pictures/FilmLab"

    fun load(ctx: Context, uri: Uri, name: String, dateTakenMs: Long): LoadedDng {
        val buf = mapFile(ctx, uri)
        val session = FilmLab.open(buf)
        var ref: ReferenceStats? = null
        var pixel: Bitmap? = null
        session.dng.previewJpeg()?.let { bytes ->
            try {
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                    // 統計用に縮小
                    val small = Bitmap.createScaledBitmap(bmp, bmp.width / 4, bmp.height / 4, true)
                    val px = IntArray(small.width * small.height)
                    small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
                    ref = ReferenceStats.fromArgb(px)
                    pixel = orient(bmp, session.meta.orientation)
                }
            } catch (_: Exception) {
            }
        }
        return LoadedDng(uri, name, dateTakenMs, session, ref, pixel)
    }

    /** DNG をメモリマップ（ヒープを使わない）。失敗したら読み込みにフォールバック */
    private fun mapFile(ctx: Context, uri: Uri): ByteBuffer {
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: throw IllegalStateException("ファイルを開けません")
        pfd.use {
            FileInputStream(it.fileDescriptor).use { fis ->
                val ch = fis.channel
                val size = ch.size()
                return try {
                    ch.map(FileChannel.MapMode.READ_ONLY, 0, size)
                } catch (e: Exception) {
                    val bytes = ByteArray(size.toInt())
                    var off = 0
                    ch.position(0)
                    while (off < bytes.size) {
                        val n = fis.read(bytes, off, bytes.size - off)
                        if (n < 0) break
                        off += n
                    }
                    ByteBuffer.wrap(bytes)
                }
            }
        }
    }

    fun renderPreview(l: LoadedDng, p: DevelopParams): Pair<Bitmap, Analysis> {
        val an = l.session.analyze(p, l.reference, PREVIEW_SIZE)
        val img = l.session.renderPreview(p, an, PREVIEW_SIZE)
        return toBitmap(img, l.session.meta.orientation) to an
    }

    /** P3 でエンコードされた画素をそのまま Display P3 のビットマップに入れる（色変換させない） */
    fun toBitmap(img: PreviewImage, orientation: Int): Bitmap {
        val bmp = Bitmap.createBitmap(img.width, img.height, Bitmap.Config.ARGB_8888, false, ColorSpace.get(ColorSpace.Named.DISPLAY_P3))
        val bytes = ByteArray(img.width * img.height * 4)
        val a = img.argb
        for (i in a.indices) {
            val c = a[i]
            bytes[i * 4] = (c shr 16).toByte()
            bytes[i * 4 + 1] = (c shr 8).toByte()
            bytes[i * 4 + 2] = c.toByte()
            bytes[i * 4 + 3] = 0xFF.toByte()
        }
        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
        return orient(bmp, orientation)
    }

    fun orient(src: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            2 -> m.postScale(-1f, 1f)
            3 -> m.postRotate(180f)
            4 -> m.postScale(1f, -1f)
            5 -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            6 -> m.postRotate(90f)
            7 -> { m.postRotate(-90f); m.postScale(-1f, 1f) }
            8 -> m.postRotate(-90f)
            else -> return src
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** フル解像度で現像して Pictures/FilmLab に保存。戻り値は保存先 URI */
    fun save(ctx: Context, l: LoadedDng, p: DevelopParams, analysis: Analysis, progress: ((Float) -> Unit)? = null): Uri {
        val resolver = ctx.contentResolver
        val display = MediaRepo.baseName(l.name) + "_" + p.stockId + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, display)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, OUTPUT_DIR)
            put(MediaStore.Images.Media.IS_PENDING, 1)
            if (l.dateTakenMs > 0) put(MediaStore.Images.Media.DATE_TAKEN, l.dateTakenMs)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IllegalStateException("保存先を作成できません")
        try {
            resolver.openOutputStream(uri, "w")?.use { os ->
                BufferedOutputStream(os, 1 shl 20).use { bos ->
                    FilmLab.exportJpeg(l.session, p, analysis, bos, progress)
                }
            } ?: throw IllegalStateException("書き込みできません")
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            return uri
        } catch (e: Throwable) {
            try {
                resolver.delete(uri, null, null)
            } catch (_: Exception) {
            }
            throw e
        }
    }
}
