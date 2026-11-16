package com.melih.altyazi

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : ComponentActivity() {

    /** Bir altyazı satırı. Önce hızlı çeviri gelir, yapay zekâ çevirisi gelince güncellenir. */
    private class Entry(val id: Int, val time: String, var who: String, var en: String, var tr: String, var notes: List<String>, var ai: Boolean = false, val engine: String = "")

    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var seenView: TextView
    private lateinit var statusView: TextView
    private lateinit var whoView: TextView
    private lateinit var badgeView: TextView
    private lateinit var trView: TextView
    private lateinit var enView: TextView
    private lateinit var notesView: TextView
    private lateinit var historyView: TextView
    private lateinit var historyScroll: ScrollView
    private lateinit var card: View
    private lateinit var speakBox: CheckBox

    private val prefs by lazy { getSharedPreferences("ayarlar", MODE_PRIVATE) }
    private val memory by lazy { StoryMemory(getSharedPreferences("hafiza", MODE_PRIVATE)) }
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val aiExecutor = Executors.newSingleThreadExecutor()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val translator = SubtitleTranslator()
    private var aiTr: AiTranslator? = null
    private var camera: Camera? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    @Volatile private var busy = false
    private var lastRun = 0L
    private val intervalMs = 250L
    private var simpleMode = false

    private var pending = ""
    private var pendingHits = 0
    private var lastShown = ""
    private var lastSeenAt = 0L
    private val history = ArrayDeque<Entry>()
    private var current: Entry? = null
    private val nextId = AtomicInteger(0)
    @Volatile private var newestId = 0
    private val clock = SimpleDateFormat("HH:mm:ss", Locale("tr", "TR"))

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else statusView.text = "Kamera izni verilmedi. Ayarlar > Uygulamalar > Altyazı Çevirmeni > İzinler'den kamerayı aç."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        previewView = findViewById(R.id.preview)
        overlay = findViewById(R.id.overlay)
        seenView = findViewById(R.id.seen)
        statusView = findViewById(R.id.status)
        whoView = findViewById(R.id.who)
        badgeView = findViewById(R.id.badge)
        trView = findViewById(R.id.tr)
        enView = findViewById(R.id.en)
        notesView = findViewById(R.id.notes)
        historyView = findViewById(R.id.history)
        historyScroll = findViewById(R.id.historyScroll)
        card = findViewById(R.id.card)
        speakBox = findViewById(R.id.speak)

        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER

        overlay.region = RectF(
            prefs.getFloat("rl", 0.10f), prefs.getFloat("rt", 0.60f),
            prefs.getFloat("rr", 0.90f), prefs.getFloat("rb", 0.95f)
        )
        overlay.onRegionChanged = { r ->
            prefs.edit().putFloat("rl", r.left).putFloat("rt", r.top)
                .putFloat("rr", r.right).putFloat("rb", r.bottom).apply()
            pending = ""; pendingHits = 0
        }

        speakBox.isChecked = prefs.getBoolean("speak", false)
        speakBox.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("speak", on).apply() }

        val fontBar = findViewById<SeekBar>(R.id.fontSize)
        fontBar.progress = prefs.getInt("font", 8)
        applyFont(fontBar.progress)
        fontBar.setOnSeekBarChangeListener(simpleSeek { p -> applyFont(p); prefs.edit().putInt("font", p).apply() })

        findViewById<Button>(R.id.clear).setOnClickListener {
            history.clear(); renderHistory()
        }
        findViewById<Button>(R.id.aiBtn).setOnClickListener { showAiDialog() }
        findViewById<Button>(R.id.simpleBtn).setOnClickListener { setSimple(true) }
        card.setOnClickListener { if (simpleMode) setSimple(false) }

        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.language = Locale("tr", "TR")
                tts?.setSpeechRate(prefs.getInt("ttsRate", 16) / 10f)
                ttsReady = true
            }
        }

        translator.deeplKey = prefs.getString("deeplKey", "")!!.trim()
        translator.prepareOffline { ok -> runOnUiThread { if (ok) refreshStatus() } }
        setupClaude()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            askCamera.launch(Manifest.permission.CAMERA)
        }
    }

    // ---------- Yapay zekâ ayarları ----------

    private fun setupClaude() {
        val on = prefs.getBoolean("aiOn", true)
        val provider = prefs.getString("provider", "gemini")!!
        val key = prefs.getString(if (provider == "claude") "apiKey" else "geminiKey", "")!!.trim()
        val def = if (provider == "claude") DEFAULT_MODEL else DEFAULT_GEMINI
        val model = prefs.getString(if (provider == "claude") "model" else "geminiModel", def)!!.trim().ifEmpty { def }
        aiTr = if (on && key.isNotEmpty()) {
            try {
                if (provider == "claude") ClaudeTranslator(key, model) else GeminiTranslator(key, model)
            } catch (e: Exception) { null }
        } else null
        refreshStatus()
    }

    private fun showAiDialog() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        val deeplTitle = TextView(this).apply { text = "Hızlı çeviri: DeepL"; textSize = 15f }
        val deeplInput = EditText(this).apply {
            hint = "DeepL API anahtarı (sonu :fx) — boş bırakılırsa Google"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("deeplKey", ""))
        }
        val aiTitle = TextView(this).apply { text = "Hikâyeyi bilen düzeltme"; textSize = 15f; setPadding(0, pad, 0, 0) }
        val onBox = CheckBox(this).apply { text = "Yapay zekâ çevirisi açık"; isChecked = prefs.getBoolean("aiOn", true) }
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val rGemini = RadioButton(this).apply { id = View.generateViewId(); text = "Gemini (ücretsiz)" }
        val rClaude = RadioButton(this).apply { id = View.generateViewId(); text = "Claude (ücretli)" }
        group.addView(rGemini); group.addView(rClaude)
        val keyInput = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val modelInput = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT }
        val help = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }

        fun fill(claudeSel: Boolean) {
            if (claudeSel) {
                keyInput.hint = "Anthropic API anahtarı (sk-ant-...)"
                keyInput.setText(prefs.getString("apiKey", ""))
                modelInput.setText(prefs.getString("model", DEFAULT_MODEL))
                help.text = "Anahtar: console.anthropic.com (ücretli). Model: claude-opus-5-5 en iyisi, claude-haiku-5-5 hızlı ve ucuz."
            } else {
                keyInput.hint = "Gemini API anahtarı (AIza...)"
                keyInput.setText(prefs.getString("geminiKey", ""))
                modelInput.setText(prefs.getString("geminiModel", DEFAULT_GEMINI))
                help.text = "Ücretsiz anahtar: aistudio.google.com/apikey adresinde Google hesabınla gir, \"Create API key\" de. Kart istemez.\nModel: $DEFAULT_GEMINI (hızlı, günlük sınırı yüksek) ya da gemini-flash-latest (daha iyi, sınırı düşük)."
            }
        }
        val claudeNow = prefs.getString("provider", "gemini") == "claude"
        (if (claudeNow) rClaude else rGemini).isChecked = true
        fill(claudeNow)
        group.setOnCheckedChangeListener { _, checked -> fill(checked == rClaude.id) }

        val mem = TextView(this).apply {
            textSize = 12f
            setPadding(0, pad / 2, 0, 0)
            text = "Hikâye hafızası: ${memory.stats()}\n\n" + memory.summary
        }
        val rateLabel = TextView(this).apply { setPadding(0, pad / 2, 0, 0) }
        val rateBar = SeekBar(this).apply { max = 15; progress = prefs.getInt("ttsRate", 16) - 5 }
        fun showRate() { rateLabel.text = "Sesli okuma hızı: ${"%.1f".format((rateBar.progress + 5) / 10f)}x" }
        showRate()
        rateBar.setOnSeekBarChangeListener(simpleSeek { showRate() })
        listOf(deeplTitle, deeplInput, aiTitle, onBox, group, keyInput, modelInput, help, rateLabel, rateBar, mem).forEach { box.addView(it) }
        val scroll = ScrollView(this).apply { addView(box) }

        AlertDialog.Builder(this)
            .setTitle("Ayarlar")
            .setView(scroll)
            .setPositiveButton("Kaydet") { _, _ ->
                val claudeSel = rClaude.isChecked
                val ed = prefs.edit().putBoolean("aiOn", onBox.isChecked).putString("provider", if (claudeSel) "claude" else "gemini")
                if (claudeSel) {
                    ed.putString("apiKey", keyInput.text.toString().trim())
                        .putString("model", modelInput.text.toString().trim().ifEmpty { DEFAULT_MODEL })
                } else {
                    ed.putString("geminiKey", keyInput.text.toString().trim())
                        .putString("geminiModel", modelInput.text.toString().trim().ifEmpty { DEFAULT_GEMINI })
                }
                ed.putInt("ttsRate", rateBar.progress + 5)
                val dk = deeplInput.text.toString().trim()
                ed.putString("deeplKey", dk)
                translator.deeplKey = dk
                ed.apply()
                tts?.setSpeechRate((rateBar.progress + 5) / 10f)
                setupClaude()
            }
            .setNeutralButton("Hafızayı sıfırla") { _, _ -> memory.reset(); refreshStatus() }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun refreshStatus() {
        val parts = ArrayList<String>()
        parts.add(if (camera != null) "Okunuyor" else "Kamera açılıyor")
        val ai = aiTr
        parts.add(if (ai != null) "yapay zekâ açık (${memory.stats()})" else "hızlı çeviri")
        ai?.lastProblem?.let { parts.add(it) }
        parts.add(if (translator.deeplKey.isNotEmpty()) "DeepL" else "Google")
        translator.deeplProblem?.let { parts.add(it) }
        if (translator.offlineReady) parts.add("çevrimdışı yedek hazır")
        statusView.text = parts.joinToString(" · ")
    }

    private fun applyFont(p: Int) {
        trView.textSize = 16f + p + (if (simpleMode) 14f else 0f)
    }

    /** Sade mod: sadece büyük Türkçe altyazı kalır. Kamera arka planda okumaya devam eder. */
    private fun setSimple(on: Boolean) {
        simpleMode = on
        val hide = if (on) View.GONE else View.VISIBLE
        findViewById<View>(R.id.leftCol).visibility = hide
        findViewById<View>(R.id.controls).visibility = hide
        statusView.visibility = hide
        historyScroll.visibility = hide
        findViewById<View>(R.id.simpleHint).visibility = if (on) View.VISIBLE else View.GONE
        trView.maxLines = if (on) 8 else 5
        applyFont(prefs.getInt("font", 8))
    }

    // ---------- Kamera ----------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val aspect = AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
            val preview = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(aspect).build())
                .build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

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
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                setupCameraControls()
                refreshStatus()
            } catch (e: Exception) {
                statusView.text = "Kamera açılamadı: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupCameraControls() {
        val cam = camera ?: return
        val zoomBar = findViewById<SeekBar>(R.id.zoom)
        zoomBar.progress = prefs.getInt("zoom", 0)
        cam.cameraControl.setLinearZoom(zoomBar.progress / 100f)
        zoomBar.setOnSeekBarChangeListener(simpleSeek { p ->
            cam.cameraControl.setLinearZoom(p / 100f)
            prefs.edit().putInt("zoom", p).apply()
        })

        val expBar = findViewById<SeekBar>(R.id.exposure)
        val state = cam.cameraInfo.exposureState
        if (state.isExposureCompensationSupported) {
            val range = state.exposureCompensationRange
            expBar.max = range.upper - range.lower
            val saved = prefs.getInt("exp", 0).coerceIn(range.lower, range.upper)
            expBar.progress = saved - range.lower
            cam.cameraControl.setExposureCompensationIndex(saved)
            expBar.setOnSeekBarChangeListener(simpleSeek { p ->
                val idx = range.lower + p
                cam.cameraControl.setExposureCompensationIndex(idx)
                prefs.edit().putInt("exp", idx).apply()
            })
        } else {
            expBar.visibility = View.GONE
            findViewById<View>(R.id.expLabel).visibility = View.GONE
        }
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        val media = proxy.image
        if (busy || media == null || now - lastRun < intervalMs) {
            proxy.close()
            return
        }
        busy = true
        lastRun = now
        val rot = proxy.imageInfo.rotationDegrees
        val uw = if (rot % 180 == 0) proxy.width else proxy.height
        val uh = if (rot % 180 == 0) proxy.height else proxy.width
        val input = InputImage.fromMediaImage(media, rot)
        recognizer.process(input)
            .addOnSuccessListener { text -> onText(text, uw, uh) }
            .addOnCompleteListener {
                proxy.close()
                busy = false
            }
    }

    private fun onText(text: Text, uw: Int, uh: Int) {
        val region = overlay.region
        val lines = ArrayList<Pair<Rect, String>>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val bb = line.boundingBox ?: continue
                val cx = bb.exactCenterX() / uw
                val cy = bb.exactCenterY() / uh
                if (region.contains(cx, cy)) lines.add(bb to line.text)
            }
        }
        lines.sortWith(compareBy({ it.first.top }, { it.first.left }))
        overlay.setDetections(lines.map { it.first }, uw, uh)

        val joined = lines.map { TextUtil.clean(it.second) }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .trim()
        seenView.text = if (joined.isEmpty()) "Görülen: —" else "Görülen: $joined"
        handle(joined)
    }

    // ---------- Çeviri akışı ----------

    private fun handle(text: String) {
        val now = SystemClock.elapsedRealtime()
        if (text.isEmpty()) {
            pending = ""; pendingHits = 0
            if (lastShown.isNotEmpty() && now - lastSeenAt > 4000) {
                card.alpha = 0.45f
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
        statusView.text = "Çevriliyor…"
        translator.translate(body, memory.recentEnglish(4)) { quick, engine ->
            runOnUiThread {
                if (quick == null && aiTr == null) {
                    statusView.text = "Çeviri yapılamadı (internet yok ve çevrimdışı model inmemiş)"
                    lastShown = ""
                    return@runOnUiThread
                }
                val ai = aiTr
                val entry = Entry(
                    nextId.incrementAndGet(), clock.format(Date()), who, body, quick ?: "…",
                    if (ai == null) Slang.notes(body) else emptyList(),
                    engine = engine
                )
                newestId = entry.id
                addEntry(entry)
                refreshStatus()
                if (ai != null) askClaude(ai, entry, quick ?: "")
                else memory.addLine(StoryMemory.Line(who, body, entry.tr))
            }
        }
    }

    private fun askClaude(ai: AiTranslator, entry: Entry, quick: String) {
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
                    renderHistory()
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

    private fun addEntry(e: Entry) {
        history.addFirst(e)
        while (history.size > 150) history.removeLast()
        current = e
        renderCurrent(e, speak = true)
        renderHistory()
    }

    private fun renderCurrent(e: Entry, speak: Boolean) {
        card.alpha = 1f
        whoView.text = e.who
        badgeView.text = if (e.ai) "yapay zekâ" else listOf(e.engine, if (aiTr != null) "düzeltiliyor" else "").filter { it.isNotEmpty() }.joinToString(" · ")
        trView.text = e.tr
        enView.text = e.en
        notesView.visibility = if (e.notes.isEmpty()) View.GONE else View.VISIBLE
        notesView.text = e.notes.joinToString("\n")
        if (speak && speakBox.isChecked && ttsReady && e.tr != "…") {
            tts?.speak(e.tr, TextToSpeech.QUEUE_FLUSH, null, "altyazi")
        }
    }

    private fun renderHistory() {
        historyView.text = history.joinToString("\n\n") { e ->
            buildString {
                append(e.time).append("  ")
                if (e.who.isNotEmpty()) append(e.who).append(": ")
                append(e.tr).append("\n      ").append(e.en)
                if (e.notes.isNotEmpty()) append("\n      ").append(e.notes.joinToString(" · "))
            }
        }
        historyScroll.scrollTo(0, 0)
    }

    private fun simpleSeek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
        aiExecutor.shutdown()
        recognizer.close()
        translator.close()
        tts?.shutdown()
    }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        const val DEFAULT_GEMINI = "gemini-flash-lite-latest"
    }
}
