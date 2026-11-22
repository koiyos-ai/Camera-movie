package filmlab.app

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Size

/** MediaStore 上の DNG（純正 Pixel カメラの RAW） */
data class DngItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val dateTakenMs: Long,
    val dateAddedSec: Long,
    val size: Long,
)

object MediaRepo {
    private val collection: Uri get() = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    /** 新しい順に DNG を列挙（Pixel は DCIM/Camera に PXL_xxx.RAW-02.ORIGINAL.dng として保存） */
    fun queryDngs(ctx: Context, sinceSec: Long = 0, limit: Int = 80): List<DngItem> {
        val proj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
        )
        val sel = "(${MediaStore.Images.Media.MIME_TYPE} = ? OR ${MediaStore.Images.Media.MIME_TYPE} = ? OR " +
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?) AND ${MediaStore.Images.Media.DATE_ADDED} >= ?"
        val args = arrayOf("image/x-adobe-dng", "image/dng", "%.dng", sinceSec.toString())
        val out = ArrayList<DngItem>()
        ctx.contentResolver.query(collection, proj, sel, args, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val iTaken = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val iAdded = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            while (c.moveToNext() && out.size < limit) {
                val id = c.getLong(iId)
                out.add(
                    DngItem(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        name = c.getString(iName) ?: "image.dng",
                        dateTakenMs = c.getLong(iTaken),
                        dateAddedSec = c.getLong(iAdded),
                        size = c.getLong(iSize),
                    )
                )
            }
        }
        return out
    }

    fun thumbnail(ctx: Context, uri: Uri, px: Int): Bitmap? = try {
        ctx.contentResolver.loadThumbnail(uri, Size(px, px), null)
    } catch (e: Exception) {
        null
    }

    /** PXL_20251122_103000123.RAW-02.ORIGINAL.dng → PXL_20251122_103000123 */
    fun baseName(name: String): String {
        val i = name.indexOf(".RAW")
        if (i > 0) return name.substring(0, i)
        return name.substringBeforeLast('.')
    }
}
