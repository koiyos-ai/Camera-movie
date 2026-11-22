package filmlab.core

import filmlab.core.dng.TiffReader
import filmlab.core.output.ExifWriter
import filmlab.core.output.IccProfile
import filmlab.core.output.JpegEncoder
import java.awt.color.ICC_Profile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.imageio.ImageIO
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OutputTest {
    @Test
    fun jpegRoundTripIsHighQualityAnd444() {
        val w = 203
        val h = 157 // 8 の倍数でない大きさも確認
        val rgb = ByteArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 3
            rgb[i] = (128 + 100 * sin(x * 0.05)).toInt().toByte()
            rgb[i + 1] = ((x * 255) / w).toByte()
            rgb[i + 2] = ((y * 255) / h).toByte()
        }
        val out = ByteArrayOutputStream()
        // ICC を付けると ImageIO が P3→sRGB 変換してしまうので、画素比較用には付けない
        val enc = JpegEncoder(out, w, h, 95, null, null)
        // 不揃いな行数で書き込んでもよい
        var y = 0
        while (y < h) {
            val n = minOf(13, h - y)
            enc.writeRows(rgb.copyOfRange(y * w * 3, (y + n) * w * 3), n)
            y += n
        }
        enc.finish()
        val bytes = out.toByteArray()
        val img = ImageIO.read(ByteArrayInputStream(bytes))
        assertEquals(w, img.width)
        assertEquals(h, img.height)
        var se = 0.0
        for (yy in 0 until h) for (x in 0 until w) {
            val p = img.getRGB(x, yy)
            val i = (yy * w + x) * 3
            val dr = ((p shr 16) and 0xFF) - (rgb[i].toInt() and 0xFF)
            val dg = ((p shr 8) and 0xFF) - (rgb[i + 1].toInt() and 0xFF)
            val db = (p and 0xFF) - (rgb[i + 2].toInt() and 0xFF)
            se += (dr * dr + dg * dg + db * db).toDouble()
        }
        val psnr = 10 * log10(255.0 * 255.0 / (se / (w * h * 3)))
        assertTrue(psnr > 38.0, "PSNR $psnr")
        // SOF0 の各成分のサンプリング係数が 1x1（4:4:4）
        var p = 2
        var found = false
        while (p < bytes.size - 4) {
            if ((bytes[p].toInt() and 0xFF) == 0xFF && (bytes[p + 1].toInt() and 0xFF) == 0xC0) {
                for (c in 0 until 3) assertEquals(0x11, bytes[p + 2 + 2 + 6 + c * 3 + 1].toInt() and 0xFF)
                found = true
                break
            }
            p++
        }
        assertTrue(found)
    }

    @Test
    fun iccProfileIsValid() {
        val prof = ICC_Profile.getInstance(IccProfile.displayP3)
        assertEquals(ICC_Profile.CLASS_DISPLAY, prof.profileClass)
        assertEquals(java.awt.color.ColorSpace.TYPE_RGB, prof.colorSpaceType)
        val g = prof.getData(ICC_Profile.icSigGreenColorantTag)
        assertTrue(g != null && g.size == 20)
    }

    @Test
    fun exifCarriesMetadata() {
        val dng = Fixtures.dng("fixture_raw.dng")
        val exif = ExifWriter.build(dng, 464, 344, "FilmLab")
        assertEquals("Exif", String(exif, 0, 4))
        val t = TiffReader(ByteBuffer.wrap(exif.copyOfRange(6, exif.size)))
        val ifd0 = t.readIfd(t.firstIfdOffset)
        assertEquals("Google", ifd0[271]!!.string())
        assertEquals(1, ifd0[274]!!.int())
        val ex = t.readIfd(ifd0[34665]!!.long())
        assertEquals(100, ex[34855]!!.int())
        assertEquals(464, ex[40962]!!.int())
        assertTrue(sqrt(ex[33434]!!.double() * 120.0) > 0.99)
    }
}
