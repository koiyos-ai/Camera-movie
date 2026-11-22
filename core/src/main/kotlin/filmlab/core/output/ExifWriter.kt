package filmlab.core.output

import filmlab.core.dng.DngFile
import filmlab.core.dng.Ifd
import filmlab.core.dng.TiffEntry
import filmlab.core.dng.tiffTypeSize
import java.io.ByteArrayOutputStream

/**
 * DNG のメタデータ（機種・撮影日時・露出・GPS など）を JPEG の EXIF(APP1) として書き出す。
 * 向き(Orientation)も引き継ぐので、画素は回転せずに済む（劣化なし）。
 */
object ExifWriter {
    private class Entry(val tag: Int, val type: Int, val count: Int, val data: ByteArray)

    private val IFD0_TAGS = setOf(271, 272, 306, 315, 33432)

    // TIFF/EP の IFD0 に置かれることがある EXIF 系タグ
    private val EXIF_TAGS_IN_IFD0 = setOf(
        33434, 33437, 34850, 34855, 34864, 34865, 34866, 36864, 36867, 36868, 36880, 36881, 36882,
        37377, 37378, 37379, 37380, 37381, 37382, 37383, 37384, 37385, 37386, 37520, 37521, 37522,
        41483, 41486, 41487, 41488, 41495, 41728, 41985, 41986, 41987, 41988, 41989, 41990, 42032, 42033,
        42034, 42035, 42036, 42037,
    )

    private val EXCLUDE_EXIF = setOf(37500 /*MakerNote*/, 40965 /*Interop*/, 40961, 40962, 40963, 37510 /*UserComment*/, 36864 /*ExifVersion*/)

    fun build(dng: DngFile, width: Int, height: Int, software: String): ByteArray {
        val (ifd0, exif, gps) = dng.exifSources()
        val e0 = ArrayList<Entry>()
        for (t in IFD0_TAGS) ifd0[t]?.let { copy(it)?.let(e0::add) }
        e0.add(short(274, dng.meta.orientation))
        e0.add(Entry(305, 2, software.length + 1, (software + "\u0000").toByteArray(Charsets.US_ASCII)))
        e0.add(rational(282, 72, 1))
        e0.add(rational(283, 72, 1))
        e0.add(short(296, 2))

        val ex = ArrayList<Entry>()
        val have = HashSet<Int>()
        exif?.let { f ->
            for ((tag, ent) in f.entries) {
                if (tag in EXCLUDE_EXIF) continue
                copy(ent)?.let { ex.add(it); have.add(tag) }
            }
        }
        for (t in EXIF_TAGS_IN_IFD0) {
            if (t in have || t in EXCLUDE_EXIF) continue
            ifd0[t]?.let { copy(it)?.let { c -> ex.add(c); have.add(t) } }
        }
        ex.add(Entry(36864, 7, 4, "0232".toByteArray(Charsets.US_ASCII)))
        ex.add(short(40961, 65535)) // 未校正（ICC プロファイルで Display P3 を指定）
        ex.add(long(40962, width))
        ex.add(long(40963, height))

        val gp = ArrayList<Entry>()
        gps?.let { f -> for ((_, ent) in f.entries) copy(ent)?.let(gp::add) }

        return assemble(e0, ex, gp)
    }

    private fun copy(e: TiffEntry): Entry? {
        val size = tiffTypeSize(e.type)
        if (size == 0 || e.type == 13) return null
        val len = size.toLong() * e.count
        if (len > 60000) return null
        return Entry(e.tag, e.type, e.count.toInt(), e.bigEndianBytes())
    }

    private fun short(tag: Int, v: Int) = Entry(tag, 3, 1, byteArrayOf((v shr 8).toByte(), v.toByte()))
    private fun long(tag: Int, v: Int) = Entry(tag, 4, 1, byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte()))
    private fun rational(tag: Int, n: Int, d: Int) = Entry(
        tag, 5, 1,
        byteArrayOf((n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte(), (d shr 24).toByte(), (d shr 16).toByte(), (d shr 8).toByte(), d.toByte()),
    )

    private fun ifdSize(entries: List<Entry>): Int {
        var s = 2 + entries.size * 12 + 4
        for (e in entries) if (e.data.size > 4) s += e.data.size + (e.data.size and 1)
        return s
    }

    private fun assemble(ifd0In: List<Entry>, exifIn: List<Entry>, gpsIn: List<Entry>): ByteArray {
        // ポインタタグを追加（値は後で決まる）
        val ifd0 = ArrayList(ifd0In)
        val ptrExif = Entry(34665, 4, 1, ByteArray(4))
        ifd0.add(ptrExif)
        val ptrGps = if (gpsIn.isNotEmpty()) Entry(34853, 4, 1, ByteArray(4)).also { ifd0.add(it) } else null
        val i0 = ifd0.sortedBy { it.tag }
        val ex = exifIn.sortedBy { it.tag }
        val gp = gpsIn.sortedBy { it.tag }
        val off0 = 8
        val offExif = off0 + ifdSize(i0)
        val offGps = offExif + ifdSize(ex)
        putInt(ptrExif.data, offExif)
        if (ptrGps != null) putInt(ptrGps.data, offGps)

        val out = ByteArrayOutputStream()
        out.write("Exif".toByteArray()); out.write(0); out.write(0)
        val tiff = ByteArrayOutputStream()
        tiff.write(byteArrayOf(0x4D, 0x4D, 0x00, 0x2A, 0, 0, 0, 8))
        writeIfd(tiff, i0, off0)
        writeIfd(tiff, ex, offExif)
        if (ptrGps != null) writeIfd(tiff, gp, offGps)
        out.write(tiff.toByteArray())
        return out.toByteArray()
    }

    private fun writeIfd(out: ByteArrayOutputStream, entries: List<Entry>, offset: Int) {
        var dataOff = offset + 2 + entries.size * 12 + 4
        val data = ByteArrayOutputStream()
        writeShort(out, entries.size)
        for (e in entries) {
            writeShort(out, e.tag)
            writeShort(out, e.type)
            writeInt(out, e.count)
            if (e.data.size <= 4) {
                out.write(e.data)
                repeat(4 - e.data.size) { out.write(0) }
            } else {
                writeInt(out, dataOff)
                data.write(e.data)
                dataOff += e.data.size
                if (e.data.size and 1 == 1) {
                    data.write(0); dataOff++
                }
            }
        }
        writeInt(out, 0)
        out.write(data.toByteArray())
    }

    private fun putInt(b: ByteArray, v: Int) {
        b[0] = (v shr 24).toByte(); b[1] = (v shr 16).toByte(); b[2] = (v shr 8).toByte(); b[3] = v.toByte()
    }

    private fun writeShort(o: ByteArrayOutputStream, v: Int) {
        o.write((v shr 8) and 0xFF); o.write(v and 0xFF)
    }

    private fun writeInt(o: ByteArrayOutputStream, v: Int) {
        o.write((v shr 24) and 0xFF); o.write((v shr 16) and 0xFF); o.write((v shr 8) and 0xFF); o.write(v and 0xFF)
    }

    @Suppress("unused")
    private fun dump(ifd: Ifd) = ifd.entries.keys.joinToString()
}
