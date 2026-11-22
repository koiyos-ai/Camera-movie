package filmlab.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import filmlab.core.film.DevelopParams
import filmlab.core.film.FilmStock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * FilmLab: 純正 Pixel カメラの RAW(DNG) をフィルム現像するアプリ（個人用・最小限の UI）。
 * 撮影は純正カメラに任せ（HDR+ の合成 RAW を得るため）、ここでは現像だけを行う。
 */
class MainActivity : Activity() {
    private val bg = Executors.newSingleThreadExecutor { r -> Thread(r, "filmlab-develop") }
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "filmlab-io") }
    private val ui = Handler(Looper.getMainLooper())

    private var params = DevelopParams()
    private var loaded: LoadedDng? = null
    private var current: DngItem? = null
    private var filmBitmap: Bitmap? = null
    private var newestId: Long = -1
    private val loadGen = AtomicInteger()
    private val renderGen = AtomicInteger()

    private lateinit var thumbRow: LinearLayout
    private lateinit var preview: ImageView
    private lateinit var status: TextView
    private lateinit var info: TextView
    private lateinit var stockDesc: TextView
    private lateinit var stockSpinner: Spinner
    private lateinit var autoSwitch: Switch
    private lateinit var aeBox: CheckBox
    private lateinit var wbBox: CheckBox
    private val sliders = ArrayList<Slider>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.colorMode = ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
        params = Prefs.params(this)
        buildUi()
        ensurePermissions()
        handleIntent(intent)
    }

    /** 純正カメラは HDR+ 処理の後で DNG を保存するので、写真ライブラリの変更を監視して一覧を更新する */
    private val refreshRunnable = Runnable { refreshList() }
    private val mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            ui.removeCallbacks(refreshRunnable)
            ui.postDelayed(refreshRunnable, 1200)
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)
        } catch (_: Exception) {
        }
        refreshList()
        val msg = Prefs.lastMessage(this)
        if (msg.isNotEmpty() && loaded == null) setStatus(msg)
    }

    override fun onPause() {
        super.onPause()
        try {
            contentResolver.unregisterContentObserver(mediaObserver)
        } catch (_: Exception) {
        }
        ui.removeCallbacks(refreshRunnable)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        bg.shutdownNow()
        io.shutdownNow()
    }

    // ------------------------------------------------------------------ UI

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun button(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun label(text: String, size: Float = 14f, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(Color.rgb(230, 230, 230))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private inner class Slider(
        val name: String,
        val minV: Float,
        val maxV: Float,
        val get: () -> Float,
        val set: (Float) -> Unit,
    ) {
        val text = label("", 13f)
        val bar = SeekBar(this@MainActivity)

        init {
            bar.max = 1000
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    set(minV + (maxV - minV) * progress / 1000f)
                    updateText()
                }

                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) = onParamsChanged()
            })
            // ラベルのダブルタップ代わりに長押しで既定値に戻す
            text.setOnLongClickListener {
                set(defaultOf(name))
                sync()
                onParamsChanged()
                true
            }
        }

        fun sync() {
            bar.progress = (((get() - minV) / (maxV - minV)) * 1000f).roundToInt().coerceIn(0, 1000)
            updateText()
        }

        fun updateText() {
            text.text = "%s  %+.2f".format(name, get())
        }
    }

    private fun defaultOf(name: String): Float {
        val d = DevelopParams()
        return when (name) {
            S_EV -> d.exposureEv; S_WARM -> d.warmth; S_TINT -> d.tint; S_GRAIN -> d.grain
            S_GSIZE -> d.grainSize; S_HAL -> d.halation; S_CON -> d.contrast; S_SAT -> d.saturation
            S_SHARP -> d.sharpen; S_NR -> d.chromaNr; else -> 0f
        }
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.rgb(18, 18, 18))
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(24))
        }
        scroll.addView(root)
        setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(button("Pixel カメラで撮影") { launchCamera() })
        autoSwitch = Switch(this).apply {
            text = "  自動現像"
            setTextColor(Color.WHITE)
            isChecked = Prefs.autoEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, on -> setAuto(on) }
        }
        top.addView(autoSwitch)
        root.addView(top)

        val hs = HorizontalScrollView(this)
        thumbRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hs.addView(thumbRow)
        root.addView(hs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(92)))

        preview = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.rgb(8, 8, 8))
            minimumHeight = dp(200)
        }
        // 押している間は Pixel 純正の処理結果（埋め込みプレビュー）を表示して比較
        preview.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> loaded?.pixelPreview?.let { preview.setImageBitmap(it) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> filmBitmap?.let { preview.setImageBitmap(it) }
            }
            true
        }
        root.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(label("プレビューを押している間は Pixel 純正の仕上がりを表示", 11f))
        status = label("", 13f)
        root.addView(status)

        root.addView(label("フィルム", 15f, true).apply { setPadding(0, dp(10), 0, 0) })
        stockSpinner = Spinner(this)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, FilmStock.ALL.map { it.label })
        stockSpinner.adapter = adapter
        stockSpinner.setSelection(FilmStock.ALL.indexOfFirst { it.id == params.stockId }.coerceAtLeast(0))
        stockSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                val st = FilmStock.ALL[pos]
                stockDesc.text = st.description + (st.kelvin?.let { "（${it.toInt()}K バランス）" } ?: "")
                if (st.id != params.stockId) {
                    params = params.copy(stockId = st.id)
                    onParamsChanged()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
        root.addView(stockSpinner)
        stockDesc = label(FilmStock.byId(params.stockId).description, 12f)
        root.addView(stockDesc)

        fun addSlider(name: String, min: Float, max: Float, get: () -> Float, set: (Float) -> Unit) {
            val s = Slider(name, min, max, get, set)
            sliders.add(s)
            root.addView(s.text.apply { setPadding(0, dp(6), 0, 0) })
            root.addView(s.bar)
            s.sync()
        }
        addSlider(S_EV, -3f, 3f, { params.exposureEv }) { params = params.copy(exposureEv = it) }
        addSlider(S_WARM, -1f, 1f, { params.warmth }) { params = params.copy(warmth = it) }
        addSlider(S_TINT, -1f, 1f, { params.tint }) { params = params.copy(tint = it) }
        addSlider(S_GRAIN, 0f, 2f, { params.grain }) { params = params.copy(grain = it) }
        addSlider(S_GSIZE, 0.5f, 2f, { params.grainSize }) { params = params.copy(grainSize = it) }
        addSlider(S_HAL, 0f, 3f, { params.halation }) { params = params.copy(halation = it) }
        addSlider(S_CON, -1f, 1f, { params.contrast }) { params = params.copy(contrast = it) }
        addSlider(S_SAT, -1f, 1f, { params.saturation }) { params = params.copy(saturation = it) }
        addSlider(S_SHARP, 0f, 2f, { params.sharpen }) { params = params.copy(sharpen = it) }
        addSlider(S_NR, 0f, 2f, { params.chromaNr }) { params = params.copy(chromaNr = it) }
        root.addView(label("（項目名を長押しで既定値に戻す）", 11f))

        aeBox = CheckBox(this).apply {
            text = "自動露出（Pixel の明るさに合わせる）"
            setTextColor(Color.WHITE)
            isChecked = params.autoExposure
            setOnCheckedChangeListener { _, c -> params = params.copy(autoExposure = c); onParamsChanged() }
        }
        root.addView(aeBox)
        wbBox = CheckBox(this).apply {
            text = "フィルム固有の色温度を使う（500T は 3200K）"
            setTextColor(Color.WHITE)
            isChecked = params.filmWhiteBalance
            setOnCheckedChangeListener { _, c -> params = params.copy(filmWhiteBalance = c); onParamsChanged() }
        }
        root.addView(wbBox)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("フル解像度で保存") { saveCurrent() })
        actions.addView(button("未現像をまとめて現像") { batchDevelop() })
        root.addView(actions)
        root.addView(button("設定をリセット") { resetParams() })

        root.addView(label("DNG 情報", 15f, true).apply { setPadding(0, dp(12), 0, 0) })
        info = label("", 11f).apply { typeface = Typeface.MONOSPACE }
        root.addView(info)
        root.addView(
            label(
                "撮影前に Pixel カメラの「設定 → 詳細設定 → RAW / JPEG の管理」を「RAW + JPEG」にしてください。" +
                    "出力は Pictures/FilmLab（JPEG 4:4:4・Display P3・EXIF 付き）。",
                11f,
            ).apply { setPadding(0, dp(12), 0, 0) }
        )
    }

    private fun setStatus(s: String) {
        status.text = s
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun syncControls() {
        sliders.forEach { it.sync() }
        aeBox.isChecked = params.autoExposure
        wbBox.isChecked = params.filmWhiteBalance
        stockSpinner.setSelection(FilmStock.ALL.indexOfFirst { it.id == params.stockId }.coerceAtLeast(0))
    }

    private fun resetParams() {
        params = DevelopParams(stockId = params.stockId)
        syncControls()
        onParamsChanged()
    }

    private fun onParamsChanged() {
        Prefs.saveParams(this, params)
        requestPreview()
    }

    // ------------------------------------------------------------------ 権限・インテント

    private fun ensurePermissions() {
        if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_MEDIA_IMAGES), REQ_MEDIA)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MEDIA) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                setStatus("写真へのアクセスを「すべて許可」にしてください（一部の写真のみだと新しい DNG を検出できません）")
            }
            refreshList()
        }
    }

    private fun handleIntent(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null) {
            var name = "shared.dng"
            try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) name = c.getString(0) ?: name
                }
            } catch (_: Exception) {
            }
            select(DngItem(-1, uri, name, 0, 0, 0))
        }
    }

    private fun launchCamera() {
        val i = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        i.setPackage("com.google.android.GoogleCamera")
        try {
            startActivity(i)
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
            } catch (e2: ActivityNotFoundException) {
                toast("カメラアプリが見つかりません")
            }
        }
    }

    private fun setAuto(on: Boolean) {
        Prefs.setAutoEnabled(this, on)
        if (on) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
            }
            AutoDevelopJob.schedule(this)
            toast("これ以降に撮影した DNG を自動でフィルム現像します")
        } else {
            AutoDevelopJob.cancel(this)
        }
    }

    // ------------------------------------------------------------------ 一覧・読み込み

    private fun refreshList() {
        if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) != PackageManager.PERMISSION_GRANTED
        ) return
        val size = dp(84)
        io.execute {
            val items = try {
                MediaRepo.queryDngs(this)
            } catch (e: Exception) {
                emptyList()
            }
            val processed = Prefs.processed(this)
            val thumbs = items.take(40).map { it to MediaRepo.thumbnail(this, it.uri, size) }
            ui.post {
                thumbRow.removeAllViews()
                for ((item, bmp) in thumbs) {
                    val iv = ImageView(this).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setImageBitmap(bmp)
                        setBackgroundColor(Color.DKGRAY)
                        alpha = if (item.id.toString() in processed) 0.55f else 1f
                        setOnClickListener { select(item) }
                    }
                    val lp = LinearLayout.LayoutParams(size, size)
                    lp.rightMargin = dp(4)
                    thumbRow.addView(iv, lp)
                }
                if (items.isEmpty()) {
                    setStatus("DNG が見つかりません。Pixel カメラで「RAW + JPEG」を有効にして撮影してください。")
                } else {
                    val top = items[0]
                    // 新しい撮影があれば自動でそれを開く
                    if (current == null || (top.id != newestId && newestId != -1L)) {
                        select(top)
                    }
                    newestId = top.id
                }
            }
        }
    }

    private fun select(item: DngItem) {
        current = item
        val g = loadGen.incrementAndGet()
        setStatus("読み込み中: ${item.name}")
        loaded = null
        filmBitmap = null
        bg.execute {
            if (g != loadGen.get()) return@execute
            try {
                System.gc()
                val t0 = SystemClock.elapsedRealtime()
                val l = Processor.load(this, item.uri, item.name, item.dateTakenMs)
                val ms = SystemClock.elapsedRealtime() - t0
                ui.post {
                    if (g == loadGen.get()) {
                        loaded = l
                        info.text = l.session.describe() + "デコード ${ms}ms"
                        l.pixelPreview?.let { preview.setImageBitmap(it) }
                        requestPreview()
                    }
                }
            } catch (e: Throwable) {
                ui.post { setStatus("読み込み失敗: ${item.name}: ${e.message ?: e.toString()}") }
            }
        }
    }

    private fun requestPreview() {
        val l = loaded ?: return
        val p = params
        val g = renderGen.incrementAndGet()
        setStatus("プレビュー現像中…")
        bg.execute {
            if (g != renderGen.get()) return@execute
            try {
                val t0 = SystemClock.elapsedRealtime()
                val (bmp, an) = Processor.renderPreview(l, p)
                val ms = SystemClock.elapsedRealtime() - t0
                ui.post {
                    if (g == renderGen.get() && loaded === l) {
                        filmBitmap = bmp
                        preview.setImageBitmap(bmp)
                        setStatus("%s  補正 %+.2f EV  (%d ms)".format(an.note, an.autoGainEv, ms))
                    }
                }
            } catch (e: Throwable) {
                ui.post { setStatus("プレビュー失敗: ${e.message ?: e.toString()}") }
            }
        }
    }

    // ------------------------------------------------------------------ 保存

    private fun saveCurrent() {
        val l = loaded ?: run { toast("DNG を選んでください"); return }
        val p = params
        val item = current
        setStatus("フル解像度で現像中…")
        bg.execute {
            try {
                val t0 = SystemClock.elapsedRealtime()
                val an = l.session.analyze(p, l.reference, Processor.PREVIEW_SIZE)
                var last = -1
                Processor.save(this, l, p, an) { f ->
                    val pct = (f * 100).toInt()
                    if (pct != last) {
                        last = pct
                        ui.post { setStatus("保存中 $pct%") }
                    }
                }
                if (item != null && item.id >= 0) Prefs.markProcessed(this, item.id)
                val sec = (SystemClock.elapsedRealtime() - t0) / 1000.0
                ui.post {
                    setStatus("保存しました: ${Processor.OUTPUT_DIR}（%.1f 秒）".format(sec))
                    toast("保存しました")
                    refreshList()
                }
            } catch (e: Throwable) {
                ui.post { setStatus("保存失敗: ${e.message ?: e.toString()}") }
            }
        }
    }

    private fun batchDevelop() {
        io.execute {
            val done = Prefs.processed(this)
            val pending = MediaRepo.queryDngs(this, 0, 200).count { it.id.toString() !in done }
            ui.post {
                if (pending == 0) {
                    toast("未現像の DNG はありません")
                    return@post
                }
                AlertDialog.Builder(this)
                    .setTitle("まとめて現像")
                    .setMessage("未現像の DNG が $pending 枚あります（新しい順に最大 200 枚まで確認）。現在の設定で現像しますか？")
                    .setPositiveButton("現像する") { _, _ ->
                        setStatus("まとめて現像中…")
                        bg.execute {
                            val n = AutoDevelopJob.runOnce(this, 0L) { name -> ui.post { setStatus("現像中: $name") } }
                            ui.post {
                                setStatus("$n 枚を現像しました")
                                refreshList()
                            }
                        }
                    }
                    .setNegativeButton("やめる", null)
                    .show()
            }
        }
    }

    companion object {
        private const val REQ_MEDIA = 1
        private const val REQ_NOTIFY = 2
        private const val S_EV = "露出補正 (EV)"
        private const val S_WARM = "色温度（+で暖色）"
        private const val S_TINT = "ティント（+でマゼンタ）"
        private const val S_GRAIN = "粒状の量"
        private const val S_GSIZE = "粒子の大きさ"
        private const val S_HAL = "ハレーション"
        private const val S_CON = "コントラスト"
        private const val S_SAT = "彩度"
        private const val S_SHARP = "シャープネス（RAW）"
        private const val S_NR = "色ノイズ低減"
    }
}
