package filmlab.core.output

import filmlab.core.raw.RowSink
import filmlab.core.util.Parallel
import java.io.OutputStream
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * ベースライン JPEG エンコーダ（行ストリーミング、クロマサブサンプリングなし 4:4:4）。
 * Android 標準の Bitmap.compress は 4:2:0 固定のため、フィルム粒子や細い色エッジが潰れる。
 * ここでは色差も全解像度で保持し、1 回だけ圧縮する。
 */
class JpegEncoder(
    private val out: OutputStream,
    private val width: Int,
    private val height: Int,
    quality: Int,
    exif: ByteArray? = null,
    icc: ByteArray? = null,
) : RowSink {
    private val q = quality.coerceIn(1, 100)
    private val bw = BitWriter(out)
    private val buf = ByteArray(width * 8 * 3)
    private var bufRows = 0
    private var rowsDone = 0
    private val dcPred = IntArray(3)

    private val qLum = scaleTable(STD_LUM)
    private val qChr = scaleTable(STD_CHR)
    private val fdLum = divisors(qLum)
    private val fdChr = divisors(qChr)

    private val dcLum = HuffTable(DC_LUM_BITS, DC_LUM_VALS)
    private val acLum = HuffTable(AC_LUM_BITS, AC_LUM_VALS)
    private val dcChr = HuffTable(DC_CHR_BITS, DC_CHR_VALS)
    private val acChr = HuffTable(AC_CHR_BITS, AC_CHR_VALS)

    private val blocksPerRow = (width + 7) / 8

    init {
        writeHeaders(exif, icc)
    }

    private fun scaleTable(std: IntArray): IntArray {
        val scale = if (q < 50) 5000 / q else 200 - q * 2
        return IntArray(64) { ((std[it] * scale + 50) / 100).coerceIn(1, 255) }
    }

    /** AAN 浮動小数 DCT 用の除数（自然順） */
    private fun divisors(qt: IntArray): FloatArray {
        val f = FloatArray(64)
        for (row in 0 until 8) for (col in 0 until 8) {
            val i = row * 8 + col
            f[i] = (1.0 / (qt[i] * AAN[row] * AAN[col] * 8.0)).toFloat()
        }
        return f
    }

    private fun writeHeaders(exif: ByteArray?, icc: ByteArray?) {
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        if (exif != null) {
            // APP1 Exif
            val len = exif.size + 2
            require(len <= 65535) { "EXIF が大きすぎます" }
            out.write(0xFF); out.write(0xE1); out.write(len shr 8); out.write(len and 0xFF)
            out.write(exif)
        } else {
            // APP0 JFIF
            val jfif = byteArrayOf(0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00)
            out.write(0xFF); out.write(0xE0); out.write(0); out.write(jfif.size + 2)
            out.write(jfif)
        }
        if (icc != null) {
            // APP2 ICC_PROFILE（64KB を超える場合は分割）
            val chunk = 65519
            val count = (icc.size + chunk - 1) / chunk
            for (i in 0 until count) {
                val s = i * chunk
                val e = minOf(icc.size, s + chunk)
                val len = 2 + 12 + 2 + (e - s)
                out.write(0xFF); out.write(0xE2); out.write(len shr 8); out.write(len and 0xFF)
                out.write("ICC_PROFILE".toByteArray(Charsets.US_ASCII)); out.write(0)
                out.write(i + 1); out.write(count)
                out.write(icc, s, e - s)
            }
        }
        // DQT
        out.write(0xFF); out.write(0xDB); out.write(0); out.write(132)
        out.write(0)
        for (i in 0 until 64) out.write(qLum[ZIGZAG_INV[i]])
        out.write(1)
        for (i in 0 until 64) out.write(qChr[ZIGZAG_INV[i]])
        // SOF0（3 成分、すべて 1x1 = 4:4:4）
        out.write(0xFF); out.write(0xC0); out.write(0); out.write(17)
        out.write(8)
        out.write(height shr 8); out.write(height and 0xFF)
        out.write(width shr 8); out.write(width and 0xFF)
        out.write(3)
        out.write(1); out.write(0x11); out.write(0)
        out.write(2); out.write(0x11); out.write(1)
        out.write(3); out.write(0x11); out.write(1)
        // DHT
        val tables = listOf(0x00 to dcLum, 0x10 to acLum, 0x01 to dcChr, 0x11 to acChr)
        var len = 2
        for ((_, t) in tables) len += 1 + 16 + t.vals.size
        out.write(0xFF); out.write(0xC4); out.write(len shr 8); out.write(len and 0xFF)
        for ((id, t) in tables) {
            out.write(id)
            for (b in t.bits) out.write(b)
            for (v in t.vals) out.write(v)
        }
        // SOS
        out.write(0xFF); out.write(0xDA); out.write(0); out.write(12)
        out.write(3)
        out.write(1); out.write(0x00)
        out.write(2); out.write(0x11)
        out.write(3); out.write(0x11)
        out.write(0); out.write(63); out.write(0)
    }

    override fun writeRows(rgb: ByteArray, rows: Int) {
        var off = 0
        val rowBytes = width * 3
        for (r in 0 until rows) {
            System.arraycopy(rgb, off, buf, bufRows * rowBytes, rowBytes)
            off += rowBytes
            bufRows++
            rowsDone++
            if (bufRows == 8) {
                encodeMcuRow(8)
                bufRows = 0
            }
        }
    }

    fun finish() {
        if (bufRows > 0) {
            encodeMcuRow(bufRows)
            bufRows = 0
        }
        bw.flush()
        out.write(0xFF); out.write(0xD9)
        out.flush()
    }

    /** 8 行分（不足分は最終行を複製）を DCT・量子化し、ハフマン符号化する */
    private fun encodeMcuRow(validRows: Int) {
        val nb = blocksPerRow
        // 量子化済み係数（ジグザグ順）を並列に計算
        val coeffs = IntArray(nb * 3 * 64)
        Parallel.forChunks(nb, 16) { s, e ->
            val yb = FloatArray(64)
            val cbb = FloatArray(64)
            val crb = FloatArray(64)
            val tmp = FloatArray(64)
            for (bx in s until e) {
                for (yy in 0 until 8) {
                    val sy = if (yy < validRows) yy else validRows - 1
                    for (xx in 0 until 8) {
                        var sx = bx * 8 + xx
                        if (sx >= width) sx = width - 1
                        val p = (sy * width + sx) * 3
                        val r = (buf[p].toInt() and 0xFF).toFloat()
                        val g = (buf[p + 1].toInt() and 0xFF).toFloat()
                        val b = (buf[p + 2].toInt() and 0xFF).toFloat()
                        val i = yy * 8 + xx
                        yb[i] = 0.299f * r + 0.587f * g + 0.114f * b - 128f
                        cbb[i] = -0.168736f * r - 0.331264f * g + 0.5f * b
                        crb[i] = 0.5f * r - 0.418688f * g - 0.081312f * b
                    }
                }
                val base = bx * 3 * 64
                fdctQuant(yb, fdLum, tmp, coeffs, base)
                fdctQuant(cbb, fdChr, tmp, coeffs, base + 64)
                fdctQuant(crb, fdChr, tmp, coeffs, base + 128)
            }
        }
        for (bx in 0 until nb) {
            val base = bx * 3 * 64
            encodeBlock(coeffs, base, 0, dcLum, acLum)
            encodeBlock(coeffs, base + 64, 1, dcChr, acChr)
            encodeBlock(coeffs, base + 128, 2, dcChr, acChr)
        }
    }

    private fun fdctQuant(data: FloatArray, div: FloatArray, tmp: FloatArray, out: IntArray, base: Int) {
        System.arraycopy(data, 0, tmp, 0, 64)
        // 行方向
        for (row in 0 until 8) aan(tmp, row * 8, 1)
        // 列方向
        for (col in 0 until 8) aan(tmp, col, 8)
        for (i in 0 until 64) {
            val v = tmp[ZIGZAG_INV[i]] * div[ZIGZAG_INV[i]]
            out[base + i] = if (v >= 0) (v + 0.5f).toInt() else -((-v + 0.5f).toInt())
        }
    }

    /** AAN 浮動小数 1D DCT（IJG jfdctflt.c と同じ） */
    private fun aan(d: FloatArray, o: Int, s: Int) {
        val tmp0 = d[o] + d[o + 7 * s]
        val tmp7 = d[o] - d[o + 7 * s]
        val tmp1 = d[o + s] + d[o + 6 * s]
        val tmp6 = d[o + s] - d[o + 6 * s]
        val tmp2 = d[o + 2 * s] + d[o + 5 * s]
        val tmp5 = d[o + 2 * s] - d[o + 5 * s]
        val tmp3 = d[o + 3 * s] + d[o + 4 * s]
        val tmp4 = d[o + 3 * s] - d[o + 4 * s]

        var tmp10 = tmp0 + tmp3
        val tmp13 = tmp0 - tmp3
        var tmp11 = tmp1 + tmp2
        var tmp12 = tmp1 - tmp2

        d[o] = tmp10 + tmp11
        d[o + 4 * s] = tmp10 - tmp11
        val z1 = (tmp12 + tmp13) * 0.707106781f
        d[o + 2 * s] = tmp13 + z1
        d[o + 6 * s] = tmp13 - z1

        tmp10 = tmp4 + tmp5
        tmp11 = tmp5 + tmp6
        tmp12 = tmp6 + tmp7
        val z5 = (tmp10 - tmp12) * 0.382683433f
        val z2 = 0.541196100f * tmp10 + z5
        val z4 = 1.306562965f * tmp12 + z5
        val z3 = tmp11 * 0.707106781f
        val z11 = tmp7 + z3
        val z13 = tmp7 - z3
        d[o + 5 * s] = z13 + z2
        d[o + 3 * s] = z13 - z2
        d[o + s] = z11 + z4
        d[o + 7 * s] = z11 - z4
    }

    private fun encodeBlock(c: IntArray, base: Int, comp: Int, dc: HuffTable, ac: HuffTable) {
        val dcv = c[base]
        val diff = dcv - dcPred[comp]
        dcPred[comp] = dcv
        writeValue(diff, dc, 0)
        var run = 0
        for (k in 1 until 64) {
            val v = c[base + k]
            if (v == 0) {
                run++
                continue
            }
            while (run > 15) {
                bw.write(ac.code[0xF0], ac.size[0xF0])
                run -= 16
            }
            writeValue(v, ac, run)
            run = 0
        }
        if (run > 0) bw.write(ac.code[0x00], ac.size[0x00])
    }

    private fun writeValue(v: Int, t: HuffTable, run: Int) {
        var a = if (v < 0) -v else v
        var nbits = 0
        while (a != 0) {
            nbits++
            a = a shr 1
        }
        val sym = (run shl 4) or nbits
        bw.write(t.code[sym], t.size[sym])
        if (nbits > 0) {
            val bits = if (v < 0) (v - 1) and ((1 shl nbits) - 1) else v
            bw.write(bits, nbits)
        }
    }

    private class BitWriter(private val out: OutputStream) {
        private var acc = 0L
        private var n = 0
        private val buf = ByteArray(1 shl 16)
        private var pos = 0

        fun write(bits: Int, count: Int) {
            if (count == 0) return
            acc = (acc shl count) or (bits.toLong() and ((1L shl count) - 1))
            n += count
            while (n >= 8) {
                val b = ((acc shr (n - 8)) and 0xFF).toInt()
                put(b)
                if (b == 0xFF) put(0)
                n -= 8
            }
        }

        private fun put(b: Int) {
            buf[pos++] = b.toByte()
            if (pos == buf.size) {
                out.write(buf, 0, pos)
                pos = 0
            }
        }

        fun flush() {
            if (n > 0) write((1 shl (8 - n)) - 1, 8 - n) // 残りを 1 で埋める
            out.write(buf, 0, pos)
            pos = 0
        }
    }

    private class HuffTable(val bits: IntArray, val vals: IntArray) {
        val code = IntArray(256)
        val size = IntArray(256)

        init {
            var c = 0
            var k = 0
            for (len in 1..16) {
                for (i in 0 until bits[len - 1]) {
                    val sym = vals[k++]
                    code[sym] = c
                    size[sym] = len
                    c++
                }
                c = c shl 1
            }
        }
    }

    companion object {
        private val AAN = DoubleArray(8) { k -> if (k == 0) 1.0 else cos(k * Math.PI / 16) * sqrt(2.0) }

        /** ZIGZAG_INV[ジグザグ順 i] = 自然順のインデックス */
        val ZIGZAG_INV = intArrayOf(
            0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5,
            12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
            35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
            58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63,
        )

        private val STD_LUM = intArrayOf(
            16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55,
            14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62,
            18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92,
            49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99,
        )
        private val STD_CHR = intArrayOf(
            17, 18, 24, 47, 99, 99, 99, 99, 18, 21, 26, 66, 99, 99, 99, 99,
            24, 26, 56, 99, 99, 99, 99, 99, 47, 66, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99,
        )

        private val DC_LUM_BITS = intArrayOf(0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0)
        private val DC_LUM_VALS = IntArray(12) { it }
        private val DC_CHR_BITS = intArrayOf(0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0)
        private val DC_CHR_VALS = IntArray(12) { it }
        private val AC_LUM_BITS = intArrayOf(0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7d)
        private val AC_LUM_VALS = intArrayOf(
            0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
            0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xa1, 0x08, 0x23, 0x42, 0xb1, 0xc1, 0x15, 0x52, 0xd1, 0xf0,
            0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0a, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x25, 0x26, 0x27, 0x28,
            0x29, 0x2a, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
            0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
            0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
            0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7,
            0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3, 0xc4, 0xc5,
            0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda, 0xe1, 0xe2,
            0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
            0xf9, 0xfa,
        )
        private val AC_CHR_BITS = intArrayOf(0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77)
        private val AC_CHR_VALS = intArrayOf(
            0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61, 0x71,
            0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91, 0xa1, 0xb1, 0xc1, 0x09, 0x23, 0x33, 0x52, 0xf0,
            0x15, 0x62, 0x72, 0xd1, 0x0a, 0x16, 0x24, 0x34, 0xe1, 0x25, 0xf1, 0x17, 0x18, 0x19, 0x1a, 0x26,
            0x27, 0x28, 0x29, 0x2a, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48,
            0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68,
            0x69, 0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
            0x88, 0x89, 0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5,
            0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3,
            0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda,
            0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
            0xf9, 0xfa,
        )
    }
}
