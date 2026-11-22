package filmlab.core

import filmlab.core.dng.DngFile
import filmlab.core.dng.GainMapOp
import filmlab.core.dng.WarpRectilinearOp
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** tools/make_test_dng.py で作った小さな合成 DNG（src/test/resources）を使うテスト */
object Fixtures {
    fun bytes(name: String): ByteArray =
        Fixtures::class.java.getResourceAsStream("/$name")?.use { it.readBytes() } ?: error("fixture $name がありません")

    fun dng(name: String) = DngFile(ByteBuffer.wrap(bytes(name)))
}

class DngDecodeTest {
    /** 同じ RAW を非圧縮・LJ92(1成分)・LJ92(2成分) で保存したものが同じ値に復号されること */
    @Test
    fun lj92MatchesUncompressed() {
        val ref = Fixtures.dng("fixture_raw.dng").decodeRaw()
        for (name in listOf("fixture_lj1.dng", "fixture_lj2.dng")) {
            val r = Fixtures.dng(name).decodeRaw()
            assertEquals(ref.width, r.width)
            assertEquals(ref.height, r.height)
            var maxDiff = 0f
            for (i in ref.data[0].indices) maxDiff = maxOf(maxDiff, abs(ref.data[0][i] - r.data[0][i]))
            assertTrue(maxDiff < 1e-6f, "$name: 最大差 $maxDiff")
        }
    }

    @Test
    fun metadataIsParsed() {
        val d = Fixtures.dng("fixture_lj2.dng")
        val m = d.meta
        assertEquals("RGGB", m.cfa.toString())
        assertEquals(480, m.rawWidth)
        assertEquals(360, m.rawHeight)
        assertEquals(7, m.compression)
        assertEquals(4095.0, m.whiteLevels[0])
        assertEquals(8, m.defaultCropX)
        assertEquals(464, m.defaultCropW)
        assertEquals(4, m.opcodes2.count { it is GainMapOp })
        assertEquals(1, m.opcodes3.count { it is WarpRectilinearOp })
        assertNotNull(m.forwardMatrix1)
        assertNotNull(d.previewJpeg(), "埋め込みプレビュー")
    }

    /** GainMap 適用後は周辺減光が打ち消され、中央と四隅の明るさの差が小さくなる */
    @Test
    fun gainMapCorrectsVignetting() {
        val raw = Fixtures.dng("fixture_raw.dng").decodeRaw()
        // 空（上部）の同じ行で、中央付近と左端付近の G 画素を比べる（空は横方向にほぼ一様）
        val y = 20
        fun meanG(x0: Int, x1: Int): Double {
            var s = 0.0
            var n = 0
            for (x in x0 until x1) if (raw.cfa!!.colorAt(x, y) == 1) {
                s += raw.data[0][y * raw.width + x]; n++
            }
            return s / n
        }
        val center = meanG(200, 280)
        val edge = meanG(4, 44)
        assertTrue(abs(edge / center - 1.0) < 0.06, "edge/center = ${edge / center}")
    }
}
