package filmlab.app

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.ExifInterface
import android.media.ImageReader
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import filmlab.core.FilmLab
import filmlab.core.film.DevelopParams
import filmlab.core.film.FilmStock
import filmlab.core.film.PreviewLut
import filmlab.core.raw.BurstMerge
import filmlab.core.raw.ReferenceStats
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * フィルムカメラ: ファインダーにフィルムの見た目をリアルタイム表示し、
 * シャッターで RAW を連写 → 位置合わせ合成 → DNG → フィルム現像して保存する。
 */
class CameraActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val camThread = HandlerThread("filmlab-camera").apply { start() }
    private val cam = Handler(camThread.looper)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "filmlab-burst") }
    private val lutWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "filmlab-lut") }

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: FilmGlRenderer
    private lateinit var status: TextView
    private lateinit var stockBtn: Button
    private lateinit var burstBtn: Button
    private lateinit var evText: TextView
    private lateinit var shutter: Button

    private var params = DevelopParams()
    private var burst = 4

    private var surfaceTexture: SurfaceTexture? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var rawReader: ImageReader? = null
    private var chars: CameraCharacteristics? = null
    private var previewBuilder: CaptureRequest.Builder? = null
    @Volatile private var lastResult: TotalCaptureResult? = null
    private var rawSize: Size? = null
    private var deviceRotation = 0
    private lateinit var orientationListener: OrientationEventListener

    // 連写中の状態（カメラスレッドでのみ触る）
    private var capturing = false
    private var expected = 0
    private val frames = ArrayList<Pair<Long, ShortArray>>()
    private val results = HashMap<Long, TotalCaptureResult>()
    private var captureRef: ReferenceStats? = null
    private var captureOrientation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        params = Prefs.params(this)
        burst = getSharedPreferences("filmlab", MODE_PRIVATE).getInt("burst", 4)
        buildUi()
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o != ORIENTATION_UNKNOWN) deviceRotation = ((o + 45) / 90 * 90) % 360
            }
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.READ_MEDIA_IMAGES), 10)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        renderer = FilmGlRenderer(
            onSurfaceTexture = { st -> ui.post { surfaceTexture = st; openCamera() } },
            requestRender = { glView.requestRender() },
        )
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }
        root.addView(glView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // 押している間はフィルムなしの映像
        glView.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> renderer.filmEnabled = false
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> renderer.filmEnabled = true
            }
            glView.requestRender()
            true
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(12))
            setBackgroundColor(Color.argb(150, 0, 0, 0))
        }
        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 12f }
        panel.addView(status)
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        stockBtn = smallButton("") { cycleStock() }
        row1.addView(stockBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        burstBtn = smallButton("") { cycleBurst() }
        row1.addView(burstBtn)
        panel.addView(row1)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row2.addView(smallButton("ラボ") { startActivity(Intent(this, MainActivity::class.java)) })
        row2.addView(smallButton("−EV") { changeEv(-0.3f) })
        evText = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f; setPadding(dp(6), 0, dp(6), 0) }
        row2.addView(evText)
        row2.addView(smallButton("+EV") { changeEv(0.3f) })
        val spacer = View(this)
        row2.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        shutter = Button(this).apply {
            text = "●"
            textSize = 28f
            setOnClickListener { shoot() }
        }
        row2.addView(shutter, LinearLayout.LayoutParams(dp(96), dp(72)))
        panel.addView(row2)
        root.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        updateLabels()
        rebuildLut()
    }

    private fun smallButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun updateLabels() {
        val st = FilmStock.byId(params.stockId)
        stockBtn.text = st.label
        burstBtn.text = "${burst}枚合成"
        evText.text = "%+.1f".format(params.exposureEv)
    }

    private fun cycleStock() {
        val all = FilmStock.ALL
        val i = all.indexOfFirst { it.id == params.stockId }
        params = params.copy(stockId = all[(i + 1) % all.size].id)
        Prefs.saveParams(this, params)
        updateLabels()
        rebuildLut()
    }

    private fun cycleBurst() {
        burst = when (burst) { 1 -> 4; 4 -> 8; else -> 1 }
        getSharedPreferences("filmlab", MODE_PRIVATE).edit().putInt("burst", burst).apply()
        updateLabels()
    }

    private fun changeEv(d: Float) {
        params = params.copy(exposureEv = (params.exposureEv + d).coerceIn(-3f, 3f))
        Prefs.saveParams(this, params)
        updateLabels()
        rebuildLut()
    }

    private fun rebuildLut() {
        val p = params
        lutWorker.execute {
            val st = FilmStock.byId(p.stockId)
            val n = 33
            val lut = PreviewLut.build(st, p, n)
            renderer.grain = PreviewLut.grainStrength(st, p)
            renderer.grainChroma = st.grainChroma.toFloat()
            renderer.setLut(lut, n)
        }
    }

    private fun setStatus(s: String) = ui.post { status.text = s }

    override fun onResume() {
        super.onResume()
        params = Prefs.params(this)
        updateLabels()
        rebuildLut()
        glView.onResume()
        orientationListener.enable()
        if (surfaceTexture != null) openCamera()
    }

    override fun onPause() {
        closeCamera()
        orientationListener.disable()
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        camThread.quitSafely()
        worker.shutdown()
        lutWorker.shutdown()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (surfaceTexture != null) openCamera()
    }

    // ------------------------------------------------------------------ Camera2

    private fun openCamera() {
        if (device != null) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            setStatus("カメラの権限が必要です")
            return
        }
        val st = surfaceTexture ?: return
        val mgr = getSystemService(CameraManager::class.java)
        val id = mgr.cameraIdList.firstOrNull { cid ->
            val c = mgr.getCameraCharacteristics(cid)
            c.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK &&
                (c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true)
        }
        if (id == null) {
            setStatus("RAW 撮影できる背面カメラが見つかりません")
            return
        }
        val c = mgr.getCameraCharacteristics(id)
        chars = c
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val raw = map.getOutputSizes(ImageFormat.RAW_SENSOR).maxBy { it.width.toLong() * it.height }
        rawSize = raw
        val aspect = raw.width.toFloat() / raw.height
        val preview = map.getOutputSizes(SurfaceTexture::class.java)
            .filter { abs(it.width.toFloat() / it.height - aspect) < 0.02f && it.width <= 2048 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: map.getOutputSizes(SurfaceTexture::class.java).first()
        st.setDefaultBufferSize(preview.width, preview.height)
        val sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        renderer.contentAspect = if (sensorOrientation % 180 == 90) preview.height.toFloat() / preview.width else preview.width.toFloat() / preview.height

        val reader = ImageReader.newInstance(raw.width, raw.height, ImageFormat.RAW_SENSOR, 10)
        reader.setOnImageAvailableListener({ r -> onRawImage(r) }, cam)
        rawReader = reader
        setStatus("RAW ${raw.width}x${raw.height} / プレビュー ${preview.width}x${preview.height}")
        try {
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    device = d
                    startSession(d, Surface(st), reader)
                }

                override fun onDisconnected(d: CameraDevice) {
                    d.close()
                    device = null
                }

                override fun onError(d: CameraDevice, error: Int) {
                    d.close()
                    device = null
                    setStatus("カメラエラー $error")
                }
            }, cam)
        } catch (e: Exception) {
            setStatus("カメラを開けません: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun startSession(d: CameraDevice, previewSurface: Surface, reader: ImageReader) {
        d.createCaptureSession(listOf(previewSurface, reader.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                b.addTarget(previewSurface)
                b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                previewBuilder = b
                try {
                    s.setRepeatingRequest(b.build(), object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                            lastResult = result
                        }
                    }, cam)
                } catch (e: Exception) {
                    setStatus("プレビュー開始に失敗: ${e.message}")
                }
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                setStatus("カメラの構成に失敗しました")
            }
        }, cam)
    }

    private fun closeCamera() {
        try {
            session?.close()
        } catch (_: Exception) {
        }
        session = null
        device?.close()
        device = null
        rawReader?.close()
        rawReader = null
        capturing = false
    }

    // ------------------------------------------------------------------ 撮影

    private fun shoot() {
        val s = session ?: return
        val d = device ?: return
        val reader = rawReader ?: return
        val c = chars ?: return
        val last = lastResult ?: return
        if (capturing) return
        val n = burst
        shutter.isEnabled = false
        // 自動露出の基準にするため、フィルムなしのプレビューを縮小して取得
        renderer.requestSnapshot { px -> captureRef = ReferenceStats.fromArgb(px) }
        val sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        captureOrientation = (sensorOrientation + deviceRotation) % 360
        cam.post {
            try {
                capturing = true
                expected = n
                frames.clear()
                results.clear()
                val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                val manual = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
                val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                b.addTarget(reader.surface)
                b.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
                val exp = last.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                val iso = last.get(CaptureResult.SENSOR_SENSITIVITY)
                if (manual && exp != null && iso != null) {
                    // 複数枚のときはハイライトを守るため 1 段アンダーで撮り、合成でノイズを補う
                    val e2 = if (n > 1) exp / 2 else exp
                    b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                    b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, e2)
                    b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                    b.set(CaptureRequest.SENSOR_FRAME_DURATION, e2)
                    val fd = last.get(CaptureResult.LENS_FOCUS_DISTANCE)
                    if (fd != null) {
                        b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                        b.set(CaptureRequest.LENS_FOCUS_DISTANCE, fd)
                    }
                } else {
                    b.set(CaptureRequest.CONTROL_AE_LOCK, true)
                }
                val req = b.build()
                s.captureBurst(List(n) { req }, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                        val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                        results[ts] = result
                        maybeFinish()
                    }

                    override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: android.hardware.camera2.CaptureFailure) {
                        expected--
                        maybeFinish()
                    }
                }, cam)
                setStatus("撮影中… ($n 枚)")
            } catch (e: Exception) {
                capturing = false
                ui.post { shutter.isEnabled = true }
                setStatus("撮影に失敗: ${e.message}")
            }
        }
    }

    /** RAW フレームを ShortArray にコピーしてすぐに解放（カメラスレッド） */
    private fun onRawImage(r: ImageReader) {
        val img = r.acquireNextImage() ?: return
        try {
            if (!capturing) return
            val plane = img.planes[0]
            val w = img.width
            val h = img.height
            val buf = plane.buffer.order(ByteOrder.nativeOrder())
            val rowStride = plane.rowStride
            val data = ShortArray(w * h)
            if (rowStride == w * 2) {
                buf.asShortBuffer().get(data, 0, w * h)
            } else {
                val sb = buf.asShortBuffer()
                for (y in 0 until h) {
                    sb.position(y * rowStride / 2)
                    sb.get(data, y * w, w)
                }
            }
            frames.add(img.timestamp to data)
            maybeFinish()
        } catch (e: Exception) {
            Log.e("FilmLab", "raw copy", e)
            expected--
        } finally {
            img.close()
        }
    }

    private fun maybeFinish() {
        if (!capturing) return
        if (frames.size < expected || results.size < expected) return
        capturing = false
        val c = chars ?: return
        val size = rawSize ?: return
        val list = frames.sortedBy { it.first }
        val res = HashMap(results)
        frames.clear()
        results.clear()
        val orientation = captureOrientation
        val ref = captureRef
        val p = params
        ui.post { shutter.isEnabled = true }
        if (list.isEmpty()) {
            setStatus("RAW を取得できませんでした")
            return
        }
        setStatus("合成・現像中…")
        worker.execute { develop(c, size, list, res, orientation, ref, p) }
    }

    private fun develop(
        c: CameraCharacteristics, size: Size, list: List<Pair<Long, ShortArray>>, res: Map<Long, TotalCaptureResult>,
        orientation: Int, ref: ReferenceStats?, p: DevelopParams,
    ) {
        try {
            val t0 = System.currentTimeMillis()
            val w = size.width
            val h = size.height
            val white = (c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023).toFloat()
            val firstResult = res[list[0].first] ?: res.values.first()
            val dyn = firstResult.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
            val black = dyn?.average()?.toFloat()
                ?: c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.let { pat -> (0 until 4).map { pat.getOffsetForIndex(it % 2, it / 2) }.average().toFloat() }
                ?: 64f
            val merged = BurstMerge.merge(list.map { it.second }, w, h, black, white)
            val refResult = res[list[merged.reference].first] ?: firstResult

            // DNG 化（色行列・レンズシェーディング・歪曲情報は DngCreator が書く）
            val dng = DngCreator(c, refResult)
            dng.setOrientation(exifOrientation(orientation))
            val bb = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder())
            bb.asShortBuffer().put(merged.raw)
            bb.position(0)
            val bos = ByteArrayOutputStream(w * h * 2 + 65536)
            dng.writeByteBuffer(bos, size, bb, 0)
            dng.close()
            val dngBytes = bos.toByteArray()

            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val name = "FILM_$stamp.dng"
            val dngUri = saveDng(name, dngBytes)
            val t1 = System.currentTimeMillis()
            setStatus("現像中… (${merged.note})")

            val session = FilmLab.open(ByteBuffer.wrap(dngBytes))
            val loaded = LoadedDng(dngUri ?: android.net.Uri.EMPTY, name, System.currentTimeMillis(), session, ref, null)
            val an = session.analyze(p, ref, Processor.PREVIEW_SIZE)
            Processor.save(this, loaded, p, an)
            val t2 = System.currentTimeMillis()
            setStatus("保存しました (${merged.note}, 合成 ${(t1 - t0) / 1000.0}s, 現像 ${(t2 - t1) / 1000.0}s)")
        } catch (t: Throwable) {
            Log.e("FilmLab", "develop", t)
            setStatus("現像に失敗: ${t.message ?: t.toString()}")
        }
    }

    private fun exifOrientation(deg: Int) = when ((deg % 360 + 360) % 360) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    /** 合成した DNG（デジタルの「ネガ」）を DCIM/FilmLab に保存。ラボで再現像できる */
    private fun saveDng(name: String, bytes: ByteArray): android.net.Uri? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/FilmLab")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return null
            // 自動現像ジョブが同じ DNG を二重に現像しないように
            Prefs.markProcessed(this, ContentUris.parseId(uri))
            contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
            contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            Log.e("FilmLab", "save dng", e)
            null
        }
    }
}
