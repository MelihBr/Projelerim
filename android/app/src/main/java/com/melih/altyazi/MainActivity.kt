package com.melih.altyazi

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    // Normal görünüm
    private lateinit var mainContent: View
    private lateinit var card: View
    private lateinit var statusView: TextView
    private lateinit var whoView: TextView
    private lateinit var badgeView: TextView
    private lateinit var trView: TextView
    private lateinit var enView: TextView
    private lateinit var notesView: TextView
    private lateinit var historyList: RecyclerView
    private lateinit var historyEmpty: View
    private lateinit var historyLayout: LinearLayoutManager

    // Sade mod
    private lateinit var simpleView: View
    private lateinit var sWho: TextView
    private lateinit var sTr: TextView
    private lateinit var sEn: TextView
    private lateinit var sNotes: TextView

    // Kamera ayarı
    private lateinit var setupView: View
    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var seenView: TextView

    private val prefs by lazy { getSharedPreferences(K.PREFS, MODE_PRIVATE) }
    private val memory by lazy { StoryMemory(getSharedPreferences("hafiza", MODE_PRIVATE)) }
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val aiExecutor = Executors.newSingleThreadExecutor()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val translator = SubtitleTranslator()
    private var aiTr: AiTranslator? = null
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // Ayarlar (onResume'da okunur)
    private var showEnSimple = false
    private var showNotes = true
    @Volatile private var batterySave = true

    @Volatile private var busy = false
    @Volatile private var region = RectF(0.10f, 0.60f, 0.90f, 0.95f)
    @Volatile private var setupOpen = false
    private var lastRun = 0L

    private var pending = ""
    private var pendingHits = 0
    private var lastShown = ""
    @Volatile private var lastSeenAt = 0L
    private val history = ArrayList<SubtitleEntry>()
    private val adapter = HistoryAdapter(history)
    private var current: SubtitleEntry? = null
    private val nextId = AtomicInteger(0)
    @Volatile private var newestId = 0
    private var simpleMode = false
    private val clock = SimpleDateFormat("HH:mm:ss", Locale("tr", "TR"))

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else statusView.text = "Kamera izni gerekli: Ayarlar > Uygulamalar > Altyazı Çevirmeni > İzinler"
    }

    private val openSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == K.RESULT_CAMERA) openSetup()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        mainContent = findViewById(R.id.mainContent)
        card = findViewById(R.id.card)
        statusView = findViewById(R.id.status)
        whoView = findViewById(R.id.who)
        badgeView = findViewById(R.id.badge)
        trView = findViewById(R.id.tr)
        enView = findViewById(R.id.en)
        notesView = findViewById(R.id.notes)
        historyList = findViewById(R.id.historyList)
        historyEmpty = findViewById(R.id.historyEmpty)
        simpleView = findViewById(R.id.simpleView)
        sWho = findViewById(R.id.sWho)
        sTr = findViewById(R.id.sTr)
        sEn = findViewById(R.id.sEn)
        sNotes = findViewById(R.id.sNotes)
        setupView = findViewById(R.id.setupView)
        previewView = findViewById(R.id.preview)
        overlay = findViewById(R.id.overlay)
        seenView = findViewById(R.id.seen)

        historyLayout = LinearLayoutManager(this)
        historyList.layoutManager = historyLayout
        historyList.adapter = adapter
        historyList.itemAnimator = null

        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        region = RectF(
            prefs.getFloat("rl", 0.10f), prefs.getFloat("rt", 0.60f),
            prefs.getFloat("rr", 0.90f), prefs.getFloat("rb", 0.95f)
        )
        overlay.region = RectF(region)
        overlay.onRegionChanged = { r ->
            region = RectF(r)
            prefs.edit().putFloat("rl", r.left).putFloat("rt", r.top)
                .putFloat("rr", r.right).putFloat("rb", r.bottom).apply()
            pending = ""; pendingHits = 0
        }

        findViewById<MaterialButton>(R.id.simpleBtn).setOnClickListener { setSimple(true) }
        findViewById<MaterialButton>(R.id.settingsBtn).setOnClickListener {
            openSettings.launch(Intent(this, SettingsActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.clear).setOnClickListener {
            history.clear(); adapter.notifyDataSetChanged(); updateEmpty()
        }
        findViewById<MaterialButton>(R.id.setupDone).setOnClickListener { closeSetup() }
        simpleView.setOnClickListener { setSimple(false) }
        updateEmpty()

        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.language = Locale("tr", "TR")
                tts?.setSpeechRate(prefs.getInt(K.TTS_RATE, 16) / 10f)
                ttsReady = true
            }
        }
        translator.prepareOffline { ok -> runOnUiThread { if (ok) refreshStatus() } }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            askCamera.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        applySettings()
    }

    /** Ayarlar ekranından dönünce her şeyi yeniden oku. */
    private fun applySettings() {
        showEnSimple = prefs.getBoolean(K.SHOW_EN_SIMPLE, false)
        showNotes = prefs.getBoolean(K.SHOW_NOTES, true)
        batterySave = prefs.getBoolean(K.BATTERY, true)
        val fontStep = prefs.getInt(K.FONT, 8)
        trView.textSize = 18f + fontStep
        sTr.textSize = 30f + fontStep
        adapter.showNotes = showNotes
        adapter.notifyDataSetChanged()
        tts?.setSpeechRate(prefs.getInt(K.TTS_RATE, 16) / 10f)
        translator.deeplKey = prefs.getString(K.DEEPL, "")!!.trim()
        memory.reload()
        setupAi()
        current?.let { renderCurrent(it, speak = false) }
    }

    private fun setupAi() {
        val on = prefs.getBoolean(K.AI_ON, true)
        val claude = prefs.getString(K.PROVIDER, "gemini") == "claude"
        val key = prefs.getString(if (claude) K.CLAUDE_KEY else K.GEMINI_KEY, "")!!.trim()
        val def = if (claude) K.DEFAULT_CLAUDE else K.DEFAULT_GEMINI
        val model = prefs.getString(if (claude) K.CLAUDE_MODEL else K.GEMINI_MODEL, def)!!.trim().ifEmpty { def }
        aiTr = if (on && key.isNotEmpty()) {
            try { if (claude) ClaudeTranslator(key, model) else GeminiTranslator(key, model) } catch (e: Exception) { null }
        } else null
        refreshStatus()
    }

    private fun refreshStatus() {
        val parts = ArrayList<String>()
        parts.add(if (camera != null) "Okunuyor" else "Kamera açılıyor")
        parts.add(if (translator.deeplKey.isNotEmpty()) "DeepL" else "Google")
        val ai = aiTr
        if (ai != null) parts.add("yapay zekâ · ${memory.stats()}")
        translator.deeplProblem?.let { parts.add(it) }
        ai?.lastProblem?.let { parts.add(it) }
        statusView.text = parts.joinToString(" · ")
    }

    // ---------- Görünümler ----------

    private fun setSimple(on: Boolean) {
        simpleMode = on
        simpleView.visibility = if (on) View.VISIBLE else View.GONE
        mainContent.visibility = if (on) View.GONE else View.VISIBLE
        val ctl = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
            if (!prefs.getBoolean(K.SIMPLE_TIP, false)) {
                Toast.makeText(this, "Normal görünüme dönmek için ekrana dokun", Toast.LENGTH_LONG).show()
                prefs.edit().putBoolean(K.SIMPLE_TIP, true).apply()
            }
        } else {
            ctl.show(WindowInsetsCompat.Type.systemBars())
        }
        current?.let { renderCurrent(it, speak = false) }
    }

    private fun openSetup() {
        setupOpen = true
        setupView.visibility = View.VISIBLE
        overlay.region = RectF(region)
        bindCamera(withPreview = true)
    }

    private fun closeSetup() {
        setupOpen = false
        setupView.visibility = View.GONE
        prefs.edit().putBoolean(K.SETUP_DONE, true).apply()
        bindCamera(withPreview = false) // önizleme kapalı: daha az pil
    }

    private fun updateEmpty() {
        historyEmpty.visibility = if (history.isEmpty()) View.VISIBLE else View.GONE
        historyList.visibility = if (history.isEmpty()) View.GONE else View.VISIBLE
    }

    // ---------- Kamera ----------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            provider = future.get()
            if (!prefs.getBoolean(K.SETUP_DONE, false)) openSetup() else bindCamera(withPreview = false)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(withPreview: Boolean) {
        val p = provider ?: return
        val aspect = AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(aspect)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            android.util.Size(1920, 1080),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy) }

        try {
            p.unbindAll()
            camera = if (withPreview) {
                val preview = Preview.Builder()
                    .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(aspect).build())
                    .build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } else {
                p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
            }
            setupCameraControls()
            refreshStatus()
        } catch (e: Exception) {
            statusView.text = "Kamera açılamadı: ${e.message}"
        }
    }

    private fun setupCameraControls() {
        val cam = camera ?: return
        val zoom = findViewById<Slider>(R.id.zoom)
        zoom.clearOnChangeListeners()
        zoom.value = prefs.getInt("zoom", 0).coerceIn(0, 100).toFloat()
        cam.cameraControl.setLinearZoom(zoom.value / 100f)
        zoom.addOnChangeListener { _, v, fromUser ->
            if (!fromUser) return@addOnChangeListener
            cam.cameraControl.setLinearZoom(v / 100f)
            prefs.edit().putInt("zoom", v.toInt()).apply()
        }

        val exp = findViewById<Slider>(R.id.exposure)
        val expLabel = findViewById<View>(R.id.expLabel)
        val state = cam.cameraInfo.exposureState
        val range = state.exposureCompensationRange
        if (state.isExposureCompensationSupported && range.upper > range.lower) {
            exp.clearOnChangeListeners()
            exp.valueFrom = range.lower.toFloat()
            exp.valueTo = range.upper.toFloat()
            val saved = prefs.getInt("exp", 0).coerceIn(range.lower, range.upper)
            exp.value = saved.toFloat()
            cam.cameraControl.setExposureCompensationIndex(saved)
            exp.addOnChangeListener { _, v, fromUser ->
                if (!fromUser) return@addOnChangeListener
                cam.cameraControl.setExposureCompensationIndex(v.toInt())
                prefs.edit().putInt("exp", v.toInt()).apply()
            }
        } else {
            exp.visibility = View.GONE
            expLabel.visibility = View.GONE
        }
    }

    /** Altyazı yokken (pil tasarrufu açıksa) daha seyrek oku. */
    private fun currentInterval(now: Long): Long =
        if (!setupOpen && batterySave && now - lastSeenAt > 6000) 700L else 250L

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (busy || now - lastRun < currentInterval(now)) { proxy.close(); return }
        busy = true
        lastRun = now
        val rot = proxy.imageInfo.rotationDegrees
        val uw = if (rot % 180 == 0) proxy.width else proxy.height
        val uh = if (rot % 180 == 0) proxy.height else proxy.width
        val r = RectF(region)

        // Sadece okuma alanını işle: daha az işlemci (pil) ve HUD yazıları karışmaz.
        var input: InputImage? = null
        var offX = 0f
        var offY = 0f
        var cropped = false
        try {
            val full = proxy.toBitmap()
            val raw = uprightToRaw(r, rot, full.width, full.height)
            if (raw.width() >= 16 && raw.height() >= 8) {
                val crop = Bitmap.createBitmap(full, raw.left, raw.top, raw.width(), raw.height())
                input = InputImage.fromBitmap(crop, rot)
                offX = r.left * uw
                offY = r.top * uh
                cropped = true
            }
        } catch (e: Exception) {
            input = null
        }
        if (input == null) {
            val media = proxy.image
            if (media == null) { proxy.close(); busy = false; return }
            input = InputImage.fromMediaImage(media, rot)
        }
        recognizer.process(input)
            .addOnSuccessListener { text -> onText(text, uw, uh, offX, offY, cropped, r) }
            .addOnCompleteListener {
                proxy.close()
                busy = false
            }
    }

    /** Dik görüntüdeki (0..1) alanı ham sensör görüntüsündeki piksel dikdörtgenine çevirir. */
    private fun uprightToRaw(r: RectF, rot: Int, w: Int, h: Int): Rect {
        fun map(ux: Float, uy: Float): Pair<Float, Float> = when (rot) {
            90 -> uy * w to (1 - ux) * h
            180 -> (1 - ux) * w to (1 - uy) * h
            270 -> (1 - uy) * w to ux * h
            else -> ux * w to uy * h
        }
        val a = map(r.left, r.top)
        val b = map(r.right, r.bottom)
        val l = min(a.first, b.first).roundToInt().coerceIn(0, w - 1)
        val t = min(a.second, b.second).roundToInt().coerceIn(0, h - 1)
        val rr = max(a.first, b.first).roundToInt().coerceIn(l + 1, w)
        val bb = max(a.second, b.second).roundToInt().coerceIn(t + 1, h)
        return Rect(l, t, rr, bb)
    }

    private fun onText(text: Text, uw: Int, uh: Int, offX: Float, offY: Float, cropped: Boolean, r: RectF) {
        val lines = ArrayList<Pair<Rect, String>>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val bb = line.boundingBox ?: continue
                val full = Rect(
                    (bb.left + offX).toInt(), (bb.top + offY).toInt(),
                    (bb.right + offX).toInt(), (bb.bottom + offY).toInt()
                )
                if (!cropped && !r.contains(full.exactCenterX() / uw, full.exactCenterY() / uh)) continue
                lines.add(full to line.text)
            }
        }
        lines.sortWith(compareBy({ it.first.top }, { it.first.left }))

        val joined = lines.map { TextUtil.clean(it.second) }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .trim()
        if (setupOpen) {
            overlay.setDetections(lines.map { it.first }, uw, uh)
            seenView.text = joined.ifEmpty { "—" }
        }
        handle(joined)
    }

    // ---------- Çeviri akışı ----------

    private fun handle(text: String) {
        val now = SystemClock.elapsedRealtime()
        if (text.isEmpty()) {
            pending = ""; pendingHits = 0
            if (lastShown.isNotEmpty() && now - lastSeenAt > 4000) {
                card.alpha = 0.55f
                sTr.alpha = 0.45f
                lastShown = ""
            }
            return
        }
        lastSeenAt = now
        if (TextUtil.similarity(text, lastShown) > 0.8) return
        if (TextUtil.similarity(text, pending) > 0.85) pendingHits++ else { pending = text; pendingHits = 1 }
        if (pendingHits < 2) return // aynı yazı iki karede görülünce çevir (titreşimi önler)

        lastShown = pending
        pendingHits = 0
        val (who, body) = TextUtil.splitSpeaker(lastShown)
        translator.translate(body, memory.recentEnglish(4)) { quick, engine ->
            runOnUiThread {
                val ai = aiTr
                if (quick == null && ai == null) {
                    statusView.text = "Çeviri yapılamadı: internet yok ve çevrimdışı model inmemiş"
                    lastShown = ""
                    return@runOnUiThread
                }
                val entry = SubtitleEntry(
                    nextId.incrementAndGet(), clock.format(Date()), who, body, quick ?: "…",
                    if (ai == null) Slang.notes(body) else emptyList(),
                    engine = engine
                )
                newestId = entry.id
                addEntry(entry)
                refreshStatus()
                if (ai != null) askAi(ai, entry, quick ?: "")
                else memory.addLine(StoryMemory.Line(who, body, entry.tr))
            }
        }
    }

    private fun askAi(ai: AiTranslator, entry: SubtitleEntry, quick: String) {
        aiExecutor.execute {
            // Sıra birikirse eski satırları atla; sadece en yeni iki satır önemli.
            if (newestId - entry.id > 1) {
                memory.addLine(StoryMemory.Line(entry.who, entry.en, entry.tr))
                return@execute
            }
            val res = try { ai.translate(entry.who, entry.en, quick, memory) } catch (e: Exception) { null }
            if (res != null) {
                res.glossary.forEach { (en, tr) -> memory.addGlossary(en, tr) }
                memory.addLine(StoryMemory.Line(entry.who, res.enFixed, res.tr))
            } else {
                memory.addLine(StoryMemory.Line(entry.who, entry.en, entry.tr))
            }
            runOnUiThread {
                if (res != null) {
                    entry.tr = res.tr; entry.en = res.enFixed; entry.notes = res.notes; entry.ai = true
                    if (current === entry) renderCurrent(entry, speak = false)
                    val idx = history.indexOf(entry)
                    if (idx >= 0) adapter.notifyItemChanged(idx)
                } else if (entry.tr == "…") {
                    entry.tr = "(çeviri alınamadı)"
                    if (current === entry) renderCurrent(entry, speak = false)
                }
                refreshStatus()
            }
            maybeUpdateSummary(ai)
        }
    }

    private fun maybeUpdateSummary(ai: AiTranslator) {
        val lines = memory.takeLinesForSummary() ?: return
        val s = try { ai.updateSummary(memory.summary, lines) } catch (e: Exception) { null }
        if (s != null) memory.setSummary(s)
    }

    private fun addEntry(e: SubtitleEntry) {
        val atTop = history.isEmpty() || historyLayout.findFirstVisibleItemPosition() <= 0
        history.add(0, e)
        adapter.notifyItemInserted(0)
        if (history.size > 300) {
            history.removeAt(history.size - 1)
            adapter.notifyItemRemoved(history.size)
        }
        if (atTop) historyList.scrollToPosition(0) // eski satırları okuyorsan yerinden oynatma
        updateEmpty()
        current = e
        renderCurrent(e, speak = true)
    }

    private fun renderCurrent(e: SubtitleEntry, speak: Boolean) {
        card.alpha = 1f
        sTr.alpha = 1f
        val notes = if (showNotes) e.notes else emptyList()

        whoView.text = e.who
        badgeView.text = if (e.ai) "yapay zekâ" else listOf(e.engine, if (aiTr != null) "düzeltiliyor" else "")
            .filter { it.isNotEmpty() }.joinToString(" · ")
        trView.text = e.tr
        enView.text = e.en
        notesView.visibility = if (notes.isEmpty()) View.GONE else View.VISIBLE
        notesView.text = notes.joinToString("\n")

        sWho.text = e.who
        sWho.visibility = if (e.who.isEmpty()) View.GONE else View.VISIBLE
        sTr.text = e.tr
        sEn.text = e.en
        sEn.visibility = if (showEnSimple && e.en.isNotEmpty()) View.VISIBLE else View.GONE
        sNotes.text = notes.joinToString("\n")
        sNotes.visibility = if (notes.isEmpty()) View.GONE else View.VISIBLE

        if (speak && prefs.getBoolean(K.SPEAK, false) && ttsReady && e.tr != "…") {
            tts?.speak(e.tr, TextToSpeech.QUEUE_FLUSH, null, "altyazi")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            setupOpen -> closeSetup()
            simpleMode -> setSimple(false)
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
        aiExecutor.shutdown()
        recognizer.close()
        translator.close()
        tts?.shutdown()
    }
}
