package filmlab.core.dng

import java.nio.ByteBuffer
import java.nio.ByteOrder

class DngException(message: String) : Exception(message)

/** TIFF 型ごとのバイト数 */
internal fun tiffTypeSize(type: Int): Int = when (type) {
    1, 2, 6, 7 -> 1
    3, 8 -> 2
    4, 9, 11, 13 -> 4
    5, 10, 12 -> 8
    16, 17, 18 -> 8
    else -> 0
}

/**
 * TIFF/DNG のバイト列を読むためのリーダー。ByteBuffer は ByteArray をラップしたものでも
 * ファイルをメモリマップしたものでもよい（大きな DNG をヒープに載せずに済む）。
 */
class TiffReader(buffer: ByteBuffer) {
    val buf: ByteBuffer = buffer.duplicate()
    val little: Boolean
    val size: Long = buf.limit().toLong()

    init {
        if (size < 8) throw DngException("ファイルが小さすぎます")
        val b0 = u8(0)
        val b1 = u8(1)
        little = when {
            b0 == 0x49 && b1 == 0x49 -> true
            b0 == 0x4D && b1 == 0x4D -> false
            else -> throw DngException("TIFF/DNG ではありません")
        }
        buf.order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        val magic = u16(2)
        if (magic != 42) throw DngException("TIFF マジックが不正です ($magic)。BigTIFF などは非対応")
    }

    fun check(off: Long, len: Int) {
        if (off < 0 || off + len > size) throw DngException("ファイル範囲外を参照しています (offset=$off)")
    }

    fun u8(off: Long): Int {
        check(off, 1)
        return buf.get(off.toInt()).toInt() and 0xFF
    }

    fun u16(off: Long): Int {
        check(off, 2)
        return buf.getShort(off.toInt()).toInt() and 0xFFFF
    }

    fun s16(off: Long): Int {
        check(off, 2)
        return buf.getShort(off.toInt()).toInt()
    }

    fun u32(off: Long): Long {
        check(off, 4)
        return buf.getInt(off.toInt()).toLong() and 0xFFFFFFFFL
    }

    fun s32(off: Long): Int {
        check(off, 4)
        return buf.getInt(off.toInt())
    }

    fun f32(off: Long): Float {
        check(off, 4)
        return buf.getFloat(off.toInt())
    }

    fun f64(off: Long): Double {
        check(off, 8)
        return buf.getDouble(off.toInt())
    }

    fun bytes(off: Long, len: Int): ByteArray {
        check(off, len)
        val out = ByteArray(len)
        val d = buf.duplicate()
        d.position(off.toInt())
        d.get(out)
        return out
    }

    val firstIfdOffset: Long get() = u32(4)

    fun readIfd(offset: Long): Ifd {
        val count = u16(offset)
        if (count == 0 || count > 4000) throw DngException("IFD のエントリ数が不正です ($count)")
        val entries = LinkedHashMap<Int, TiffEntry>(count * 2)
        for (i in 0 until count) {
            val e = offset + 2 + i * 12L
            val tag = u16(e)
            val type = u16(e + 2)
            val cnt = u32(e + 4)
            val sz = tiffTypeSize(type)
            if (sz == 0) continue
            val total = sz.toLong() * cnt
            val valueOff = if (total <= 4) e + 8 else u32(e + 8)
            if (total > 4 && (valueOff < 0 || valueOff + total > size)) continue // 壊れたエントリは無視
            entries[tag] = TiffEntry(tag, type, cnt, valueOff, this)
        }
        val nextOff = offset + 2 + count * 12L
        val next = if (nextOff + 4 <= size) u32(nextOff) else 0L
        return Ifd(offset, entries, next)
    }
}

class TiffEntry(
    val tag: Int,
    val type: Int,
    val count: Long,
    val valueOffset: Long,
    private val r: TiffReader,
) {
    val byteLength: Long get() = tiffTypeSize(type).toLong() * count

    fun long(i: Int = 0): Long {
        val o = valueOffset + i.toLong() * tiffTypeSize(type)
        return when (type) {
            1, 7 -> r.u8(o).toLong()
            6 -> r.u8(o).toByte().toLong()
            3 -> r.u16(o).toLong()
            8 -> r.s16(o).toLong()
            4, 13 -> r.u32(o)
            9 -> r.s32(o).toLong()
            5, 10, 11, 12 -> double(i).toLong()
            else -> throw DngException("tag $tag: 整数として読めない型 $type")
        }
    }

    fun int(i: Int = 0): Int = long(i).toInt()

    fun double(i: Int = 0): Double {
        val o = valueOffset + i.toLong() * tiffTypeSize(type)
        return when (type) {
            5 -> {
                val n = r.u32(o).toDouble()
                val d = r.u32(o + 4).toDouble()
                if (d == 0.0) 0.0 else n / d
            }
            10 -> {
                val n = r.s32(o).toDouble()
                val d = r.s32(o + 4).toDouble()
                if (d == 0.0) 0.0 else n / d
            }
            11 -> r.f32(o).toDouble()
            12 -> r.f64(o)
            else -> long(i).toDouble()
        }
    }

    fun doubles(): DoubleArray = DoubleArray(count.toInt()) { double(it) }

    fun ints(): IntArray = IntArray(count.toInt()) { int(it) }

    fun longs(): LongArray = LongArray(count.toInt()) { long(it) }

    fun bytes(): ByteArray = r.bytes(valueOffset, byteLength.toInt())

    fun string(): String {
        val b = bytes()
        var end = b.size
        while (end > 0 && b[end - 1].toInt() == 0) end--
        return String(b, 0, end, Charsets.UTF_8).trim()
    }

    /** 値を big-endian の生バイト列として取得（EXIF 書き出し用）。 */
    fun bigEndianBytes(): ByteArray {
        val sz = tiffTypeSize(type)
        val raw = bytes()
        if (!r.little || sz == 1) return raw
        val out = raw.copyOf()
        val unit = when (type) {
            5, 10 -> 4 // RATIONAL は 4byte 整数 2 つ
            else -> sz
        }
        var i = 0
        while (i + unit <= out.size) {
            for (k in 0 until unit) out[i + k] = raw[i + unit - 1 - k]
            i += unit
        }
        return out
    }
}

class Ifd(val offset: Long, val entries: Map<Int, TiffEntry>, val next: Long) {
    val subIfds = ArrayList<Ifd>()
    var exifIfd: Ifd? = null
    var gpsIfd: Ifd? = null

    operator fun get(tag: Int): TiffEntry? = entries[tag]
    fun has(tag: Int) = entries.containsKey(tag)
    fun int(tag: Int, def: Int): Int = entries[tag]?.int() ?: def
    fun long(tag: Int, def: Long): Long = entries[tag]?.long() ?: def

    val width: Int get() = int(Tags.IMAGE_WIDTH, 0)
    val height: Int get() = int(Tags.IMAGE_LENGTH, 0)
    val newSubfileType: Int get() = int(Tags.NEW_SUBFILE_TYPE, 0)
    val photometric: Int get() = int(Tags.PHOTOMETRIC, -1)
    val compression: Int get() = int(Tags.COMPRESSION, 1)
}

object Tags {
    const val NEW_SUBFILE_TYPE = 254
    const val IMAGE_WIDTH = 256
    const val IMAGE_LENGTH = 257
    const val BITS_PER_SAMPLE = 258
    const val COMPRESSION = 259
    const val PHOTOMETRIC = 262
    const val MAKE = 271
    const val MODEL = 272
    const val STRIP_OFFSETS = 273
    const val ORIENTATION = 274
    const val SAMPLES_PER_PIXEL = 277
    const val ROWS_PER_STRIP = 278
    const val STRIP_BYTE_COUNTS = 279
    const val PLANAR_CONFIG = 284
    const val SOFTWARE = 305
    const val DATE_TIME = 306
    const val PREDICTOR = 317
    const val TILE_WIDTH = 322
    const val TILE_LENGTH = 323
    const val TILE_OFFSETS = 324
    const val TILE_BYTE_COUNTS = 325
    const val SUB_IFDS = 330
    const val SAMPLE_FORMAT = 339
    const val JPEG_IF_OFFSET = 513
    const val JPEG_IF_LENGTH = 514
    const val CFA_REPEAT_PATTERN_DIM = 33421
    const val CFA_PATTERN = 33422
    const val EXIF_IFD = 34665
    const val GPS_IFD = 34853
    const val DNG_VERSION = 50706
    const val UNIQUE_CAMERA_MODEL = 50708
    const val CFA_PLANE_COLOR = 50710
    const val CFA_LAYOUT = 50711
    const val LINEARIZATION_TABLE = 50712
    const val BLACK_LEVEL_REPEAT_DIM = 50713
    const val BLACK_LEVEL = 50714
    const val BLACK_LEVEL_DELTA_H = 50715
    const val BLACK_LEVEL_DELTA_V = 50716
    const val WHITE_LEVEL = 50717
    const val DEFAULT_SCALE = 50718
    const val DEFAULT_CROP_ORIGIN = 50719
    const val DEFAULT_CROP_SIZE = 50720
    const val COLOR_MATRIX_1 = 50721
    const val COLOR_MATRIX_2 = 50722
    const val CAMERA_CALIBRATION_1 = 50723
    const val CAMERA_CALIBRATION_2 = 50724
    const val ANALOG_BALANCE = 50727
    const val AS_SHOT_NEUTRAL = 50728
    const val AS_SHOT_WHITE_XY = 50729
    const val BASELINE_EXPOSURE = 50730
    const val BASELINE_NOISE = 50731
    const val BASELINE_SHARPNESS = 50732
    const val LINEAR_RESPONSE_LIMIT = 50734
    const val CALIBRATION_ILLUMINANT_1 = 50778
    const val CALIBRATION_ILLUMINANT_2 = 50779
    const val ACTIVE_AREA = 50829
    const val FORWARD_MATRIX_1 = 50964
    const val FORWARD_MATRIX_2 = 50965
    const val OPCODE_LIST_1 = 51008
    const val OPCODE_LIST_2 = 51009
    const val OPCODE_LIST_3 = 51022
    const val NOISE_PROFILE = 51041
    const val PROFILE_GAIN_TABLE_MAP = 52525
}
