package filmlab.core

import filmlab.core.dng.DngFile
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DngDecodeTest {
    private fun fixture(name: String): File? {
        val f = File("../tools/samples/$name")
        return if (f.exists()) f else null
    }

    private fun open(f: File) = RandomAccessFile(f, "r").use { DngFile(it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length())) }

    /** 同じシードで作った非圧縮 DNG と LJ92 DNG の復号結果が一致すること */
    @Test
    fun lj92MatchesUncompressed() {
        for ((a, b) in listOf("test_cmp_raw.dng" to "test_cmp_lj1.dng", "test_cmp_raw.dng" to "test_cmp_lj2.dng")) {
            val fa = fixture(a) ?: return
            val fb = fixture(b) ?: return
            val ra = open(fa).decodeRaw()
            val rb = open(fb).decodeRaw()
            assertEquals(ra.width, rb.width)
            assertEquals(ra.height, rb.height)
            var maxDiff = 0f
            var where = -1
            val da = ra.data[0]
            val db = rb.data[0]
            for (i in da.indices) {
                val d = abs(da[i] - db[i])
                if (d > maxDiff) {
                    maxDiff = d; where = i
                }
            }
            assertTrue(maxDiff < 1e-6f, "$b: 最大差 $maxDiff at (${where % ra.width}, ${where / ra.width})")
        }
    }
}
