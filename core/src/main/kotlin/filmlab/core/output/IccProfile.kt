package filmlab.core.output

import filmlab.core.raw.ColorMath
import filmlab.core.util.Mat3
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.math.roundToInt

/**
 * Display P3 の ICC v4 プロファイル（行列 + パラメトリック sRGB トーンカーブ）を生成する。
 * Pixel 純正カメラも Display P3 で保存するため、色域を狭めずに済む。
 */
object IccProfile {
    val displayP3: ByteArray by lazy { build("Display P3", ColorMath.P3_TO_XYZ) }

    fun build(description: String, rgbToXyzD65: Mat3): ByteArray {
        val chad = ColorMath.bradford(ColorMath.D65_XY, ColorMath.D50_XY)
        val m = chad * rgbToXyzD65 // D50 に順応した原色
        val tags = ArrayList<Pair<String, ByteArray>>()
        tags.add("desc" to mluc(description))
        tags.add("cprt" to mluc("No copyright, use freely"))
        tags.add("wtpt" to xyz(0.9642, 1.0, 0.8249))
        tags.add("rXYZ" to xyz(m[0, 0], m[1, 0], m[2, 0]))
        tags.add("gXYZ" to xyz(m[0, 1], m[1, 1], m[2, 1]))
        tags.add("bXYZ" to xyz(m[0, 2], m[1, 2], m[2, 2]))
        val trc = para()
        tags.add("rTRC" to trc)
        tags.add("gTRC" to trc)
        tags.add("bTRC" to trc)
        tags.add("chad" to sf32(chad))

        // タグデータ配置（同一内容は共有）
        val headerSize = 128
        val tableSize = 4 + tags.size * 12
        val offsets = IntArray(tags.size)
        val sizes = IntArray(tags.size)
        val data = ByteArrayOutputStream()
        var pos = headerSize + tableSize
        val seen = HashMap<ByteArray, Int>()
        for ((i, t) in tags.withIndex()) {
            val existing = seen[t.second]
            if (existing != null) {
                offsets[i] = existing
                sizes[i] = t.second.size
                continue
            }
            offsets[i] = pos
            sizes[i] = t.second.size
            seen[t.second] = pos
            data.write(t.second)
            pos += t.second.size
            while (pos % 4 != 0) {
                data.write(0); pos++
            }
        }
        val total = pos
        val out = ByteArrayOutputStream(total)
        val d = DataOutputStream(out)
        // ヘッダ
        d.writeInt(total)
        d.writeInt(0) // CMM
        d.writeInt(0x04300000) // v4.3
        d.write("mntr".toByteArray())
        d.write("RGB ".toByteArray())
        d.write("XYZ ".toByteArray())
        // 日時 2024-01-01 00:00:00
        for (v in intArrayOf(2024, 1, 1, 0, 0, 0)) d.writeShort(v)
        d.write("acsp".toByteArray())
        d.writeInt(0) // platform
        d.writeInt(0) // flags
        d.writeInt(0) // manufacturer
        d.writeInt(0) // model
        d.writeLong(0) // attributes
        d.writeInt(0) // rendering intent: perceptual
        d.writeInt(s15(0.9642)); d.writeInt(s15(1.0)); d.writeInt(s15(0.8249))
        d.writeInt(0) // creator
        repeat(16) { d.write(0) } // profile ID
        repeat(28) { d.write(0) } // reserved
        // タグテーブル
        d.writeInt(tags.size)
        for ((i, t) in tags.withIndex()) {
            d.write(t.first.toByteArray())
            d.writeInt(offsets[i])
            d.writeInt(sizes[i])
        }
        d.write(data.toByteArray())
        d.flush()
        return out.toByteArray()
    }

    private fun s15(v: Double): Int = (v * 65536.0).roundToInt()

    private fun xyz(x: Double, y: Double, z: Double): ByteArray {
        val o = ByteArrayOutputStream()
        val d = DataOutputStream(o)
        d.write("XYZ ".toByteArray()); d.writeInt(0)
        d.writeInt(s15(x)); d.writeInt(s15(y)); d.writeInt(s15(z))
        return o.toByteArray()
    }

    private fun sf32(m: Mat3): ByteArray {
        val o = ByteArrayOutputStream()
        val d = DataOutputStream(o)
        d.write("sf32".toByteArray()); d.writeInt(0)
        for (v in m.m) d.writeInt(s15(v))
        return o.toByteArray()
    }

    /** parametricCurveType 関数 3（sRGB と同形） */
    private fun para(): ByteArray {
        val o = ByteArrayOutputStream()
        val d = DataOutputStream(o)
        d.write("para".toByteArray()); d.writeInt(0)
        d.writeShort(3); d.writeShort(0)
        d.writeInt(s15(2.4))
        d.writeInt(s15(1.0 / 1.055))
        d.writeInt(s15(0.055 / 1.055))
        d.writeInt(s15(1.0 / 12.92))
        d.writeInt(s15(0.04045))
        return o.toByteArray()
    }

    private fun mluc(text: String): ByteArray {
        val o = ByteArrayOutputStream()
        val d = DataOutputStream(o)
        val str = text.toByteArray(Charsets.UTF_16BE)
        d.write("mluc".toByteArray()); d.writeInt(0)
        d.writeInt(1) // records
        d.writeInt(12) // record size
        d.write("en".toByteArray()); d.write("US".toByteArray())
        d.writeInt(str.size)
        d.writeInt(28)
        d.write(str)
        return o.toByteArray()
    }
}
