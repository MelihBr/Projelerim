package com.melih.altyazi

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
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

class MainActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var seenView: TextView
    private lateinit var statusView: TextView
    private lateinit var whoView: TextView
    private lateinit var trView: TextView
    private lateinit var enView: TextView
    private lateinit var notesView: TextView
    private lateinit var historyView: TextView
    private lateinit var card: View
    private lateinit var speakBox: CheckBox

    private val prefs by lazy { getSharedPreferences("ayarlar", MODE_PRIVATE) }
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val translator = SubtitleTranslator()
    private var camera: Camera? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    @Volatile private var busy = false
    private var lastRun = 0L
    private val intervalMs = 350L

    private var pending = ""
    private var pendingHits = 0
    private var lastShown = ""
    private var lastSeenAt = 0L
    private val history = ArrayDeque<String>()
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
        trView = findViewById(R.id.tr)
        enView = findViewById(R.id.en)
        notesView = findViewById(R.id.notes)
        historyView = findViewById(R.id.history)
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
        fontBar.progress = prefs.getInt("font", 10)
        applyFont(fontBar.progress)
        fontBar.setOnSeekBarChangeListener(simpleSeek { p -> applyFont(p); prefs.edit().putInt("font", p).apply() })

        findViewById<Button>(R.id.clear).setOnClickListener {
            history.clear(); historyView.text = ""
        }

        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.language = Locale("tr", "TR")
                ttsReady = true
            }
        }

        translator.prepareOffline { ok ->
            runOnUiThread { if (ok) setStatusBase("Çevrimdışı çeviri hazır") }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            askCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun applyFont(p: Int) {
        trView.textSize = 16f + p
    }

    private var statusBase = ""
    private fun setStatusBase(s: String) {
        statusBase = s
        statusView.text = s
    }

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
                setStatusBase(if (translator.offlineReady) "Okunuyor · çevrimdışı çeviri hazır" else "Okunuyor")
            } catch (e: Exception) {
                setStatusBase("Kamera açılamadı: ${e.message}")
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
        translator.translate(body) { tr ->
            runOnUiThread {
                if (tr == null) {
                    statusView.text = "Çeviri yapılamadı (internet yok ve çevrimdışı model inmemiş)"
                    lastShown = ""
                } else {
                    show(who, body, tr)
                    statusView.text = statusBase
                }
            }
        }
    }

    private fun show(who: String, en: String, tr: String) {
        card.alpha = 1f
        whoView.visibility = if (who.isEmpty()) View.GONE else View.VISIBLE
        whoView.text = who
        trView.text = tr
        enView.text = en
        val notes = Slang.notes(en)
        notesView.visibility = if (notes.isEmpty()) View.GONE else View.VISIBLE
        notesView.text = notes.joinToString("\n")

        val time = clock.format(Date())
        val entry = buildString {
            append(time).append("  ")
            if (who.isNotEmpty()) append(who).append(": ")
            append(tr).append("\n      ").append(en)
            if (notes.isNotEmpty()) append("\n      ").append(notes.joinToString(" · "))
        }
        history.addFirst(entry)
        while (history.size > 150) history.removeLast()
        historyView.text = history.joinToString("\n\n")

        if (speakBox.isChecked && ttsReady) {
            tts?.speak(tr, TextToSpeech.QUEUE_FLUSH, null, "altyazi")
        }
    }

    private fun simpleSeek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
        recognizer.close()
        translator.close()
        tts?.shutdown()
    }
}
