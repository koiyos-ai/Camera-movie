package filmlab.core.dng

import java.nio.ByteBuffer

/**
 * ロスレス JPEG (ITU-T T.81 Process 14, いわゆる LJ92) デコーダ。
 * DNG の Compression=7 で使われる。Pixel 10 系の DNG もこの形式。
 *
 * 復号したサンプルは「JPEG の行ごと」に onRow(行番号, サンプル配列) で返す。
 * 1 行には width * components 個のサンプルが成分インターリーブ順で入る。
 */
class LosslessJpegDecoder(private val buf: ByteBuffer, private val start: Int, private val length: Int) {
    var width = 0
        private set
    var height = 0
        private set
    var components = 0
        private set
    var precision = 0
        private set

    private class Huff(val lookup: IntArray) // (len << 8) | symbol, 16bit 先読み

    private val tables = arrayOfNulls<Huff>(4)
    private val compIds = IntArray(4)
    private val compTable = IntArray(4)
    private var predictor = 1
    private var pointTransform = 0
    private var restartInterval = 0
    private var scanStart = -1
    private val end = start + length

    private fun u8(p: Int): Int {
        if (p >= end) throw DngException("LJ92: データが途中で終わっています")
        return buf.get(p).toInt() and 0xFF
    }

    private fun u16(p: Int): Int = (u8(p) shl 8) or u8(p + 1)

    /** マーカーを解析してスキャン開始位置まで進める。 */
    fun parseHeader() {
        var p = start
        if (u8(p) != 0xFF || u8(p + 1) != 0xD8) throw DngException("LJ92: SOI がありません")
        p += 2
        var sawFrame = false
        while (p < end) {
            if (u8(p) != 0xFF) {
                p++
                continue
            }
            var marker = u8(p + 1)
            p += 2
            while (marker == 0xFF) { // フィルバイト
                marker = u8(p)
                p++
            }
            when (marker) {
                0xD8, 0x01 -> {}
                in 0xD0..0xD7 -> {}
                0xC3 -> { // SOF3 lossless
                    val len = u16(p)
                    precision = u8(p + 2)
                    height = u16(p + 3)
                    width = u16(p + 5)
                    components = u8(p + 7)
                    if (components !in 1..4) throw DngException("LJ92: 成分数 $components は非対応")
                    for (c in 0 until components) {
                        compIds[c] = u8(p + 8 + c * 3)
                        val hv = u8(p + 9 + c * 3)
                        if (hv != 0x11) throw DngException("LJ92: サブサンプリング付きは非対応")
                    }
                    sawFrame = true
                    p += len
                }
                0xC0, 0xC1, 0xC2, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF ->
                    throw DngException("LJ92: ロスレスではない JPEG です (SOF${marker - 0xC0})。非対応")
                0xC4 -> { // DHT
                    val len = u16(p)
                    var q = p + 2
                    val e = p + len
                    while (q < e) {
                        val tcth = u8(q)
                        val th = tcth and 0x0F
                        val counts = IntArray(17)
                        var total = 0
                        for (i in 1..16) {
                            counts[i] = u8(q + i)
                            total += counts[i]
                        }
                        val symbols = IntArray(total) { u8(q + 17 + it) }
                        if (th > 3) throw DngException("LJ92: Huffman テーブル番号不正")
                        tables[th] = buildHuff(counts, symbols)
                        q += 17 + total
                    }
                    p += len
                }
                0xDD -> { // DRI
                    restartInterval = u16(p + 2)
                    p += u16(p)
                }
                0xDA -> { // SOS
                    val len = u16(p)
                    val ns = u8(p + 2)
                    if (ns != components) throw DngException("LJ92: 複数スキャンは非対応")
                    for (i in 0 until ns) {
                        val cid = u8(p + 3 + i * 2)
                        val td = u8(p + 4 + i * 2) shr 4
                        val idx = (0 until components).firstOrNull { compIds[it] == cid } ?: i
                        compTable[idx] = td
                    }
                    predictor = u8(p + 3 + ns * 2)
                    pointTransform = u8(p + 5 + ns * 2) and 0x0F
                    scanStart = p + len
                    if (!sawFrame) throw DngException("LJ92: SOF3 がありません")
                    return
                }
                0xD9 -> throw DngException("LJ92: スキャンがありません")
                else -> p += u16(p) // APPn, COM など
            }
        }
        throw DngException("LJ92: SOS が見つかりません")
    }

    private fun buildHuff(counts: IntArray, symbols: IntArray): Huff {
        val lookup = IntArray(1 shl 16)
        var code = 0
        var k = 0
        for (len in 1..16) {
            for (i in 0 until counts[len]) {
                val sym = symbols[k++]
                val shift = 16 - len
                val base = code shl shift
                val n = 1 shl shift
                if (base + n <= lookup.size) {
                    val v = (len shl 8) or sym
                    for (j in 0 until n) lookup[base + j] = v
                }
                code++
            }
            code = code shl 1
        }
        return Huff(lookup)
    }

    // ---- ビットリーダー ----
    private var pos = 0
    private var bitBuf = 0L
    private var bitCnt = 0
    private var hitMarker = false

    private fun fill() {
        while (bitCnt <= 56) {
            var b = 0
            if (!hitMarker && pos < end) {
                b = buf.get(pos).toInt() and 0xFF
                if (b == 0xFF) {
                    val nb = if (pos + 1 < end) buf.get(pos + 1).toInt() and 0xFF else 0xD9
                    if (nb == 0x00) {
                        pos += 2
                    } else {
                        hitMarker = true // マーカー（RST など）。以降は 0 を供給
                        b = 0
                    }
                } else {
                    pos++
                }
            }
            bitBuf = bitBuf or (b.toLong() shl (56 - bitCnt))
            bitCnt += 8
        }
    }

    private fun peek16(): Int {
        if (bitCnt < 16) fill()
        return (bitBuf ushr 48).toInt() and 0xFFFF
    }

    private fun skip(n: Int) {
        bitBuf = bitBuf shl n
        bitCnt -= n
    }

    private fun readBits(n: Int): Int {
        if (n == 0) return 0
        if (bitCnt < n) fill()
        val v = (bitBuf ushr (64 - n)).toInt()
        skip(n)
        return v
    }

    private fun decodeDiff(h: Huff): Int {
        val e = h.lookup[peek16()]
        val len = e shr 8
        if (len == 0) throw DngException("LJ92: 不正な Huffman 符号")
        skip(len)
        val s = e and 0xFF
        return when (s) {
            0 -> 0
            16 -> 32768
            else -> {
                val v = readBits(s)
                if (v < (1 shl (s - 1))) v - (1 shl s) + 1 else v
            }
        }
    }

    /** RST マーカーを読み飛ばしてビットバッファをリセット */
    private fun handleRestart() {
        // バイト境界に揃えて次のマーカーを探す
        bitBuf = 0
        bitCnt = 0
        hitMarker = false
        while (pos + 1 < end) {
            if ((buf.get(pos).toInt() and 0xFF) == 0xFF) {
                val m = buf.get(pos + 1).toInt() and 0xFF
                if (m in 0xD0..0xD7) {
                    pos += 2
                    return
                }
                if (m != 0xFF && m != 0x00) return
            }
            pos++
        }
    }

    fun decode(onRow: (row: Int, samples: IntArray) -> Unit) {
        if (scanStart < 0) parseHeader()
        for (c in 0 until components) {
            if (tables[compTable[c]] == null) throw DngException("LJ92: Huffman テーブルがありません")
        }
        pos = scanStart
        bitBuf = 0
        bitCnt = 0
        hitMarker = false
        val w = width
        val nc = components
        val rowLen = w * nc
        var prev = IntArray(rowLen)
        var cur = IntArray(rowLen)
        val initPred = 1 shl (precision - pointTransform - 1)
        val pt = pointTransform
        val huffs = Array(nc) { tables[compTable[it]]!! }
        val mcusPerRow = w
        if (restartInterval != 0 && restartInterval % mcusPerRow != 0) {
            throw DngException("LJ92: 行境界に揃わないリスタート間隔は非対応")
        }
        val rowsPerRestart = if (restartInterval == 0) Int.MAX_VALUE else restartInterval / mcusPerRow
        var rowInInterval = 0
        val out = IntArray(rowLen)
        for (row in 0 until height) {
            if (rowsPerRestart != Int.MAX_VALUE && row > 0 && rowInInterval == rowsPerRestart) {
                handleRestart()
                rowInInterval = 0
            }
            val firstRow = rowInInterval == 0
            for (col in 0 until w) {
                val base = col * nc
                for (c in 0 until nc) {
                    val diff = decodeDiff(huffs[c])
                    val pred: Int = if (firstRow) {
                        if (col == 0) initPred else cur[base - nc + c]
                    } else if (col == 0) {
                        prev[c]
                    } else {
                        val ra = cur[base - nc + c]
                        val rb = prev[base + c]
                        val rc = prev[base - nc + c]
                        when (predictor) {
                            1 -> ra
                            2 -> rb
                            3 -> rc
                            4 -> ra + rb - rc
                            5 -> ra + ((rb - rc) shr 1)
                            6 -> rb + ((ra - rc) shr 1)
                            7 -> (ra + rb) ushr 1
                            else -> ra
                        }
                    }
                    cur[base + c] = (pred + diff) and 0xFFFF
                }
            }
            if (pt != 0) {
                for (i in 0 until rowLen) out[i] = cur[i] shl pt
            } else {
                System.arraycopy(cur, 0, out, 0, rowLen)
            }
            onRow(row, out)
            val t = prev
            prev = cur
            cur = t
            rowInInterval++
        }
    }
}
