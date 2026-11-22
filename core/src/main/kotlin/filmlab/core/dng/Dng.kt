package filmlab.core.dng

import filmlab.core.util.Mat3
import filmlab.core.util.Parallel
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/** 2x2 ベイヤー配列。colors[(y&1)*2 + (x&1)] = 0:R 1:G 2:B（ActiveArea 左上基準） */
class CfaPattern(val colors: IntArray) {
    fun colorAt(x: Int, y: Int): Int = colors[((y and 1) shl 1) or (x and 1)]
    override fun toString(): String = colors.joinToString("") { "RGB"[it].toString() }
}

/**
 * ステージ2（リニア化・黒レベル減算・正規化済み、ActiveArea で切り出し済み）の RAW 画像。
 * planes=1: CFA（ベイヤー）, planes=3: LinearRaw（デモザイク済み）
 */
class RawImage(
    val width: Int,
    val height: Int,
    val planes: Int,
    val data: Array<FloatArray>,
    val cfa: CfaPattern?,
)

/** 現像に必要な DNG メタデータ */
class DngMeta(
    val make: String,
    val model: String,
    val uniqueModel: String,
    val orientation: Int,
    val dngVersion: String,
    val colorMatrix1: Mat3?,
    val colorMatrix2: Mat3?,
    val forwardMatrix1: Mat3?,
    val forwardMatrix2: Mat3?,
    val cameraCalibration1: Mat3?,
    val cameraCalibration2: Mat3?,
    val analogBalance: DoubleArray?,
    val illuminant1: Int,
    val illuminant2: Int,
    val asShotNeutral: DoubleArray?,
    val asShotWhiteXY: DoubleArray?,
    val baselineExposure: Double,
    val noiseProfile: DoubleArray?,
    val rawWidth: Int,
    val rawHeight: Int,
    /** top, left, bottom, right */
    val activeArea: IntArray,
    val cfa: CfaPattern?,
    val samplesPerPixel: Int,
    val bitsPerSample: Int,
    val compression: Int,
    val photometric: Int,
    val tiled: Boolean,
    val defaultCropX: Int,
    val defaultCropY: Int,
    val defaultCropW: Int,
    val defaultCropH: Int,
    val opcodes1: List<Opcode>,
    val opcodes2: List<Opcode>,
    val opcodes3: List<Opcode>,
    val hasProfileGainTableMap: Boolean,
    val whiteLevels: DoubleArray,
    val blackLevelSummary: String,
) {
    val activeWidth get() = activeArea[3] - activeArea[1]
    val activeHeight get() = activeArea[2] - activeArea[0]

    fun describe(): String = buildString {
        appendLine("カメラ: $make $model ($uniqueModel)  DNG $dngVersion")
        appendLine("RAW: ${rawWidth}x$rawHeight, ${if (photometric == 32803) "CFA ${cfa}" else "LinearRaw"} , spp=$samplesPerPixel, ${bitsPerSample}bit, 圧縮=${compressionName(compression)}${if (tiled) " (タイル)" else ""}")
        appendLine("ActiveArea: ${activeArea.joinToString()}  DefaultCrop: ${defaultCropX},${defaultCropY} ${defaultCropW}x$defaultCropH  Orientation=$orientation")
        appendLine("Black: $blackLevelSummary  White: ${whiteLevels.joinToString { "%.0f".format(it) }}")
        appendLine("AsShotNeutral: ${asShotNeutral?.joinToString { "%.4f".format(it) } ?: "-"}  BaselineExposure: ${"%.2f".format(baselineExposure)} EV")
        appendLine("Illuminant: $illuminant1 / $illuminant2  ForwardMatrix: ${if (forwardMatrix1 != null) "あり" else "なし"}  NoiseProfile: ${noiseProfile?.joinToString { "%.2e".format(it) } ?: "-"}")
        appendLine("OpcodeList1: ${opcodes1.joinToString { it.name }.ifEmpty { "-" }}")
        appendLine("OpcodeList2: ${opcodes2.joinToString { it.name }.ifEmpty { "-" }}")
        appendLine("OpcodeList3: ${opcodes3.joinToString { it.name }.ifEmpty { "-" }}")
        if (hasProfileGainTableMap) appendLine("ProfileGainTableMap: あり（ローカルトーンマップ。フィルム現像では未使用）")
    }

    companion object {
        fun compressionName(c: Int) = when (c) {
            1 -> "非圧縮"
            7 -> "ロスレスJPEG"
            8 -> "Deflate"
            34892 -> "非可逆JPEG"
            52546 -> "JPEG XL"
            else -> c.toString()
        }
    }
}

class DngFile(buffer: ByteBuffer) {
    val tiff = TiffReader(buffer)
    val ifd0: Ifd
    val allIfds = ArrayList<Ifd>()
    val rawIfd: Ifd
    val meta: DngMeta

    init {
        // IFD チェーンと SubIFD を全部たどる
        val visited = HashSet<Long>()
        val top = ArrayList<Ifd>()
        var off = tiff.firstIfdOffset
        while (off != 0L && visited.add(off) && top.size < 16) {
            val ifd = tiff.readIfd(off)
            top.add(ifd)
            off = ifd.next
        }
        if (top.isEmpty()) throw DngException("IFD がありません")
        ifd0 = top[0]
        fun walk(ifd: Ifd, depth: Int) {
            allIfds.add(ifd)
            if (depth > 4) return
            ifd[Tags.SUB_IFDS]?.let { e ->
                for (i in 0 until e.count.toInt()) {
                    val o = e.long(i)
                    if (o > 0 && visited.add(o)) {
                        try {
                            val s = tiff.readIfd(o)
                            ifd.subIfds.add(s)
                            walk(s, depth + 1)
                        } catch (_: DngException) {
                        }
                    }
                }
            }
            ifd[Tags.EXIF_IFD]?.let { e ->
                val o = e.long()
                if (o > 0 && visited.add(o)) try { ifd.exifIfd = tiff.readIfd(o) } catch (_: DngException) {}
            }
            ifd[Tags.GPS_IFD]?.let { e ->
                val o = e.long()
                if (o > 0 && visited.add(o)) try { ifd.gpsIfd = tiff.readIfd(o) } catch (_: DngException) {}
            }
        }
        for (t in top) walk(t, 0)
        if (!ifd0.has(Tags.DNG_VERSION)) throw DngException("DNG ではありません（DNGVersion タグなし）")

        val candidates = allIfds.filter { it.photometric == 32803 || it.photometric == 34892 }
        val main = candidates.filter { it.newSubfileType == 0 }.ifEmpty { candidates }
        rawIfd = main.maxByOrNull { it.width.toLong() * it.height } ?: throw DngException("RAW 画像の IFD が見つかりません")
        meta = readMeta()
    }

    /** raw IFD → IFD0 の順に探す（DNG ではどちらに置かれていてもよいタグがある） */
    private fun find(tag: Int): TiffEntry? = rawIfd[tag] ?: ifd0[tag]
    private fun findProfile(tag: Int): TiffEntry? = ifd0[tag] ?: rawIfd[tag]

    private fun matrix(tag: Int): Mat3? {
        val e = findProfile(tag) ?: return null
        if (e.count != 9L) {
            if (e.count == 12L || e.count == 16L) throw DngException("4色センサーの DNG は非対応です")
            return null
        }
        return Mat3(e.doubles())
    }

    private fun readMeta(): DngMeta {
        val r = rawIfd
        val w = r.width
        val h = r.height
        val spp = r.int(Tags.SAMPLES_PER_PIXEL, 1)
        val bps = r[Tags.BITS_PER_SAMPLE]?.int() ?: 16
        val aa = r[Tags.ACTIVE_AREA]?.ints() ?: intArrayOf(0, 0, h, w)
        val active = if (aa.size == 4 && aa[2] > aa[0] && aa[3] > aa[1] && aa[2] <= h && aa[3] <= w) aa else intArrayOf(0, 0, h, w)
        val aw = active[3] - active[1]
        val ah = active[2] - active[0]

        var cfa: CfaPattern? = null
        if (r.photometric == 32803) {
            val dim = r[Tags.CFA_REPEAT_PATTERN_DIM]?.ints() ?: intArrayOf(2, 2)
            if (dim[0] != 2 || dim[1] != 2) throw DngException("2x2 ベイヤー以外の CFA (${dim[0]}x${dim[1]}) は非対応です")
            val pat = r[Tags.CFA_PATTERN]?.bytes() ?: throw DngException("CFAPattern がありません")
            val planeColor = r[Tags.CFA_PLANE_COLOR]?.bytes() ?: byteArrayOf(0, 1, 2)
            val colors = IntArray(4) { i ->
                val code = pat[i].toInt()
                val idx = planeColor.indexOfFirst { it.toInt() == code }
                if (idx < 0 || idx > 2) throw DngException("RGB 以外の CFA 色は非対応です")
                idx
            }
            if (colors.count { it == 1 } != 2 || colors.count { it == 0 } != 1) throw DngException("ベイヤー配列ではありません")
            cfa = CfaPattern(colors)
        } else if (spp != 3) {
            throw DngException("LinearRaw で SamplesPerPixel=$spp は非対応です")
        }

        val white = r[Tags.WHITE_LEVEL]?.doubles() ?: doubleArrayOf(((1L shl bps) - 1).toDouble())
        val whites = DoubleArray(spp) { if (it < white.size) white[it] else white[0] }
        val blackDim = r[Tags.BLACK_LEVEL_REPEAT_DIM]?.ints() ?: intArrayOf(1, 1)
        val blacks = r[Tags.BLACK_LEVEL]?.doubles() ?: doubleArrayOf(0.0)

        val cropO = find(Tags.DEFAULT_CROP_ORIGIN)?.doubles()
        val cropS = find(Tags.DEFAULT_CROP_SIZE)?.doubles()
        var cx = cropO?.getOrNull(0)?.toInt() ?: 0
        var cy = cropO?.getOrNull(1)?.toInt() ?: 0
        var cw = cropS?.getOrNull(0)?.toInt() ?: aw
        var ch = cropS?.getOrNull(1)?.toInt() ?: ah
        if (cx < 0 || cy < 0 || cw <= 0 || ch <= 0 || cx + cw > aw || cy + ch > ah) {
            cx = 0; cy = 0; cw = aw; ch = ah
        }

        val ver = ifd0[Tags.DNG_VERSION]?.bytes()?.joinToString(".") { (it.toInt() and 0xFF).toString() } ?: "?"
        val ab = findProfile(Tags.ANALOG_BALANCE)?.doubles()?.takeIf { it.size == 3 }
        return DngMeta(
            make = ifd0[Tags.MAKE]?.string() ?: "",
            model = ifd0[Tags.MODEL]?.string() ?: "",
            uniqueModel = ifd0[Tags.UNIQUE_CAMERA_MODEL]?.string() ?: "",
            orientation = (ifd0[Tags.ORIENTATION] ?: rawIfd[Tags.ORIENTATION])?.int()?.takeIf { it in 1..8 } ?: 1,
            dngVersion = ver,
            colorMatrix1 = matrix(Tags.COLOR_MATRIX_1),
            colorMatrix2 = matrix(Tags.COLOR_MATRIX_2),
            forwardMatrix1 = matrix(Tags.FORWARD_MATRIX_1),
            forwardMatrix2 = matrix(Tags.FORWARD_MATRIX_2),
            cameraCalibration1 = matrix(Tags.CAMERA_CALIBRATION_1),
            cameraCalibration2 = matrix(Tags.CAMERA_CALIBRATION_2),
            analogBalance = ab,
            illuminant1 = findProfile(Tags.CALIBRATION_ILLUMINANT_1)?.int() ?: 0,
            illuminant2 = findProfile(Tags.CALIBRATION_ILLUMINANT_2)?.int() ?: 0,
            asShotNeutral = find(Tags.AS_SHOT_NEUTRAL)?.doubles()?.takeIf { it.size == 3 },
            asShotWhiteXY = find(Tags.AS_SHOT_WHITE_XY)?.doubles()?.takeIf { it.size == 2 },
            baselineExposure = findProfile(Tags.BASELINE_EXPOSURE)?.double() ?: 0.0,
            noiseProfile = find(Tags.NOISE_PROFILE)?.doubles(),
            rawWidth = w,
            rawHeight = h,
            activeArea = active,
            cfa = cfa,
            samplesPerPixel = spp,
            bitsPerSample = bps,
            compression = r.compression,
            photometric = r.photometric,
            tiled = r.has(Tags.TILE_OFFSETS),
            defaultCropX = cx,
            defaultCropY = cy,
            defaultCropW = cw,
            defaultCropH = ch,
            opcodes1 = Opcodes.parse(r[Tags.OPCODE_LIST_1]?.bytes()),
            opcodes2 = Opcodes.parse(r[Tags.OPCODE_LIST_2]?.bytes()),
            opcodes3 = Opcodes.parse(r[Tags.OPCODE_LIST_3]?.bytes()),
            hasProfileGainTableMap = find(Tags.PROFILE_GAIN_TABLE_MAP) != null,
            whiteLevels = whites,
            blackLevelSummary = "${blackDim.joinToString("x")}: " + blacks.take(8).joinToString { "%.1f".format(it) },
        )
    }

    /** 埋め込みプレビュー（ベースライン JPEG）の生バイト。Pixel では HDR+ 処理後の縮小画像。 */
    fun previewJpeg(): ByteArray? {
        var best: ByteArray? = null
        var bestPixels = 0L
        for (ifd in allIfds) {
            if (ifd === rawIfd) continue
            val comp = ifd.compression
            val bytes: ByteArray? = try {
                if (ifd.has(Tags.JPEG_IF_OFFSET) && ifd.has(Tags.JPEG_IF_LENGTH)) {
                    tiff.bytes(ifd[Tags.JPEG_IF_OFFSET]!!.long(), ifd[Tags.JPEG_IF_LENGTH]!!.int())
                } else if ((comp == 7 || comp == 6) && ifd.photometric != 32803 && ifd.photometric != 34892 &&
                    ifd.has(Tags.STRIP_OFFSETS) && ifd[Tags.STRIP_OFFSETS]!!.count == 1L
                ) {
                    tiff.bytes(ifd[Tags.STRIP_OFFSETS]!!.long(), ifd[Tags.STRIP_BYTE_COUNTS]!!.int())
                } else null
            } catch (_: DngException) {
                null
            }
            if (bytes == null || bytes.size < 4 || (bytes[0].toInt() and 0xFF) != 0xFF || (bytes[1].toInt() and 0xFF) != 0xD8) continue
            // ベースライン JPEG（SOF0/SOF1/SOF2）か確認
            if (!isBaselineJpeg(bytes)) continue
            val px = ifd.width.toLong() * ifd.height
            if (best == null || px > bestPixels) {
                best = bytes
                bestPixels = px
            }
        }
        return best
    }

    private fun isBaselineJpeg(b: ByteArray): Boolean {
        var p = 2
        while (p + 4 < b.size) {
            if ((b[p].toInt() and 0xFF) != 0xFF) return false
            val m = b[p + 1].toInt() and 0xFF
            if (m == 0xC0 || m == 0xC1 || m == 0xC2) return true
            if (m == 0xC3) return false
            if (m == 0xDA || m == 0xD9) return false
            val len = ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
            p += 2 + len
        }
        return false
    }

    // ------------------------------------------------------------------
    // RAW デコード
    // ------------------------------------------------------------------

    fun decodeRaw(): RawImage {
        val m = meta
        val r = rawIfd
        val spp = m.samplesPerPixel
        val bps = m.bitsPerSample
        val w = m.rawWidth
        val h = m.rawHeight
        val aT = m.activeArea[0]
        val aL = m.activeArea[1]
        val aw = m.activeWidth
        val ah = m.activeHeight
        val planes = spp
        val data = Array(planes) { FloatArray(aw * ah) }

        // --- リニア化パラメータ ---
        val linTable = r[Tags.LINEARIZATION_TABLE]?.ints()
        val blackDim = r[Tags.BLACK_LEVEL_REPEAT_DIM]?.ints()?.takeIf { it.size == 2 && it[0] > 0 && it[1] > 0 } ?: intArrayOf(1, 1)
        val bRows = blackDim[0]
        val bCols = blackDim[1]
        val bl = r[Tags.BLACK_LEVEL]?.doubles() ?: doubleArrayOf(0.0)
        val blackRep = FloatArray(bRows * bCols * spp) { i -> (if (i < bl.size) bl[i] else bl[0]).toFloat() }
        val dH = r[Tags.BLACK_LEVEL_DELTA_H]?.doubles()?.takeIf { it.size == aw }
        val dV = r[Tags.BLACK_LEVEL_DELTA_V]?.doubles()?.takeIf { it.size == ah }
        val deltaH = FloatArray(aw) { (dH?.get(it) ?: 0.0).toFloat() }
        val deltaV = FloatArray(ah) { (dV?.get(it) ?: 0.0).toFloat() }
        val whites = FloatArray(spp) { m.whiteLevels[it].toFloat() }
        val lut: FloatArray? = linTable?.let { t -> FloatArray(t.size) { t[it].toFloat() } }

        // サンプル (raw 座標 x, y, plane s) の値 v を正規化して格納
        fun store(x: Int, y: Int, s: Int, v: Int) {
            val ax = x - aL
            val ay = y - aT
            if (ax < 0 || ay < 0 || ax >= aw || ay >= ah) return
            val lin = if (lut != null) lut[min(v, lut.size - 1)] else v.toFloat()
            val black = blackRep[((ay % bRows) * bCols + (ax % bCols)) * spp + s] + deltaH[ax] + deltaV[ay]
            val range = whites[s] - black
            data[s][ay * aw + ax] = if (range > 0f) (lin - black) / range else 0f
        }

        val comp = m.compression
        if (comp != 1 && comp != 7) {
            throw DngException("この DNG の圧縮形式（${DngMeta.compressionName(comp)}）には未対応です")
        }
        if (r.int(Tags.PLANAR_CONFIG, 1) != 1 && spp > 1) throw DngException("PlanarConfiguration=2 は非対応です")

        // タイル/ストリップ配置
        val tiled = r.has(Tags.TILE_OFFSETS)
        val tw: Int
        val th: Int
        val offsets: LongArray
        val counts: LongArray
        if (tiled) {
            tw = r.int(Tags.TILE_WIDTH, w)
            th = r.int(Tags.TILE_LENGTH, h)
            offsets = r[Tags.TILE_OFFSETS]!!.longs()
            counts = r[Tags.TILE_BYTE_COUNTS]?.longs() ?: LongArray(offsets.size) { tiff.size - offsets[it] }
        } else {
            tw = w
            th = min(h, r.int(Tags.ROWS_PER_STRIP, h).let { if (it <= 0) h else it })
            offsets = r[Tags.STRIP_OFFSETS]?.longs() ?: throw DngException("StripOffsets がありません")
            counts = r[Tags.STRIP_BYTE_COUNTS]?.longs() ?: LongArray(offsets.size) { tiff.size - offsets[it] }
        }
        val across = (w + tw - 1) / tw
        val nBlocks = offsets.size

        if (comp == 1) {
            if (bps != 8 && bps != 16 && bps > 16) throw DngException("${bps}bit 非圧縮は非対応です")
            val bytesPerRow = (tw.toLong() * spp * bps + 7) / 8
            // 非圧縮: 行単位で並列化
            val blockRows = IntArray(nBlocks) { th }
            Parallel.forChunks(nBlocks * th, 64) { s, e ->
                for (k in s until e) {
                    val blk = k / th
                    val ry = k % th
                    val bx = (blk % across) * tw
                    val by = (blk / across) * th
                    val y = by + ry
                    if (y >= h || ry >= blockRows[blk]) continue
                    val rowOff = offsets[blk] + ry * bytesPerRow
                    if (rowOff + bytesPerRow > tiff.size) continue
                    val n = min(tw, w - bx)
                    when (bps) {
                        16 -> for (i in 0 until n * spp) {
                            val v = tiff.buf.getShort((rowOff + i * 2).toInt()).toInt() and 0xFFFF
                            store(bx + i / spp, y, i % spp, v)
                        }
                        8 -> for (i in 0 until n * spp) {
                            store(bx + i / spp, y, i % spp, tiff.u8(rowOff + i))
                        }
                        else -> {
                            // ビットパック（MSB first、行はバイト境界）
                            var bitPos = 0L
                            for (i in 0 until n * spp) {
                                var v = 0
                                for (b in 0 until bps) {
                                    val byte = tiff.u8(rowOff + (bitPos shr 3))
                                    val bit = (byte shr (7 - (bitPos and 7).toInt())) and 1
                                    v = (v shl 1) or bit
                                    bitPos++
                                }
                                store(bx + i / spp, y, i % spp, v)
                            }
                        }
                    }
                }
            }
        } else {
            // ロスレス JPEG: ブロック（タイル/ストリップ）単位で並列化
            Parallel.forChunks(nBlocks, 1) { s, e ->
                for (blk in s until e) {
                    val bx = (blk % across) * tw
                    val by = (blk / across) * th
                    if (by >= h) continue
                    val off = offsets[blk]
                    val cnt = min(counts[blk], tiff.size - off).toInt()
                    val dec = LosslessJpegDecoder(tiff.buf, off.toInt(), cnt)
                    dec.parseHeader()
                    val rowLen = dec.width * dec.components
                    val tileRowLen = tw * spp
                    dec.decode { jr, samples ->
                        if (rowLen == tileRowLen) {
                            val y = by + jr
                            if (y < h) {
                                val n = min(tileRowLen, (w - bx) * spp)
                                for (i in 0 until n) store(bx + i / spp, y, i % spp, samples[i])
                            }
                        } else {
                            // JPEG の行とタイルの行が一致しない場合（例: 半分の高さで2倍幅）
                            val base = jr.toLong() * rowLen
                            for (i in 0 until rowLen) {
                                val idx = base + i
                                val ty = (idx / tileRowLen).toInt()
                                val rem = (idx % tileRowLen).toInt()
                                val tx = rem / spp
                                val sp = rem % spp
                                val x = bx + tx
                                val y = by + ty
                                if (x < w && y < h && ty < th) store(x, y, sp, samples[i])
                            }
                        }
                    }
                }
            }
        }

        val raw = RawImage(aw, ah, planes, data, m.cfa)
        applyStage2Opcodes(raw)
        return raw
    }

    /** OpcodeList1（ほぼ不良画素のみ）と OpcodeList2（GainMap など）をステージ2画像に適用 */
    private fun applyStage2Opcodes(raw: RawImage) {
        val m = meta
        for (op in m.opcodes1) {
            if (op is FixBadPixelsListOp && raw.planes == 1) {
                Opcodes.applyBadPixels(op, raw.data[0], raw.width, raw.height, raw.cfa, m.activeArea[0], m.activeArea[1])
            }
        }
        val pointOps = ArrayList<Opcode>()
        for (op in m.opcodes2) {
            when (op) {
                is FixBadPixelsListOp -> if (raw.planes == 1) {
                    Opcodes.applyPointOps(pointOps, raw.data, raw.width, raw.height, 0, 0, raw.width, raw.height, true)
                    pointOps.clear()
                    Opcodes.applyBadPixels(op, raw.data[0], raw.width, raw.height, raw.cfa, 0, 0)
                }
                is GainMapOp, is MapPolynomialOp, is MapTableOp, is PerRowColOp, is FixVignetteRadialOp -> pointOps.add(op)
                else -> {}
            }
        }
        if (pointOps.isNotEmpty()) {
            Opcodes.applyPointOps(pointOps, raw.data, raw.width, raw.height, 0, 0, raw.width, raw.height, true)
        }
    }

    /** EXIF として引き継ぐタグ群（IFD0 / ExifIFD / GPS） */
    fun exifSources(): Triple<Ifd, Ifd?, Ifd?> = Triple(ifd0, ifd0.exifIfd ?: allIfds.firstNotNullOfOrNull { it.exifIfd }, ifd0.gpsIfd ?: allIfds.firstNotNullOfOrNull { it.gpsIfd })

    companion object {
        fun open(bytes: ByteArray) = DngFile(ByteBuffer.wrap(bytes))
    }
}

internal fun clampInt(v: Int, lo: Int, hi: Int) = max(lo, min(hi, v))
