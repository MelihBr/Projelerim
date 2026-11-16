package com.melih.altyazi

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class SettingsActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences(K.PREFS, MODE_PRIVATE) }
    private lateinit var deepl: TextInputEditText
    private lateinit var aiOn: MaterialSwitch
    private lateinit var aiKey: TextInputEditText
    private lateinit var aiKeyLayout: TextInputLayout
    private lateinit var aiModel: TextInputEditText
    private lateinit var aiHelp: TextView
    private var claudeSelected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<MaterialButton>(R.id.back).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.cameraBtn).setOnClickListener {
            save()
            setResult(K.RESULT_CAMERA)
            finish()
        }

        // Kamera / pil
        bindSwitch(R.id.batterySave, K.BATTERY, true)

        // Görünüm
        bindSwitch(R.id.showEnSimple, K.SHOW_EN_SIMPLE, false)
        bindSwitch(R.id.showNotes, K.SHOW_NOTES, true)
        bindSwitch(R.id.speak, K.SPEAK, false)

        val fontLabel = findViewById<TextView>(R.id.fontLabel)
        val font = findViewById<Slider>(R.id.fontSlider)
        font.value = prefs.getInt(K.FONT, 8).coerceIn(0, 30).toFloat()
        fun showFont() { fontLabel.text = "Yazı boyutu: ${(18 + font.value).toInt()}" }
        showFont()
        font.addOnChangeListener { _, v, _ -> prefs.edit().putInt(K.FONT, v.toInt()).apply(); showFont() }

        val rateLabel = findViewById<TextView>(R.id.rateLabel)
        val rate = findViewById<Slider>(R.id.rateSlider)
        rate.value = prefs.getInt(K.TTS_RATE, 16).coerceIn(5, 20).toFloat()
        fun showRate() { rateLabel.text = "Okuma hızı: ${"%.1f".format(rate.value / 10f)}x" }
        showRate()
        rate.addOnChangeListener { _, v, _ -> prefs.edit().putInt(K.TTS_RATE, v.toInt()).apply(); showRate() }

        // Hızlı çeviri
        deepl = findViewById(R.id.deeplKey)
        deepl.setText(prefs.getString(K.DEEPL, ""))

        // Yapay zekâ
        aiOn = findViewById(R.id.aiOn)
        aiOn.isChecked = prefs.getBoolean(K.AI_ON, true)
        aiKey = findViewById(R.id.aiKey)
        aiKeyLayout = findViewById(R.id.aiKeyLayout)
        aiModel = findViewById(R.id.aiModel)
        aiHelp = findViewById(R.id.aiHelp)
        claudeSelected = prefs.getString(K.PROVIDER, "gemini") == "claude"
        val group = findViewById<MaterialButtonToggleGroup>(R.id.providerGroup)
        group.check(if (claudeSelected) R.id.provClaude else R.id.provGemini)
        fillProvider()
        group.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val nowClaude = id == R.id.provClaude
            if (nowClaude == claudeSelected) return@addOnButtonCheckedListener
            saveAiFields()
            claudeSelected = nowClaude
            fillProvider()
        }

        // Hafıza
        val memText = findViewById<TextView>(R.id.memText)
        fun showMem() {
            val m = StoryMemory(getSharedPreferences("hafiza", MODE_PRIVATE))
            memText.text = m.stats().replaceFirstChar { it.uppercase() } +
                if (m.summary.isNotEmpty()) "\n\n" + m.summary else ""
        }
        showMem()
        findViewById<MaterialButton>(R.id.resetMem).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Hafıza sıfırlansın mı?")
                .setMessage("Öğrenilen hikâye özeti, isimler ve son replikler silinir. Yeni bir oyuna başlarken kullan.")
                .setPositiveButton("Sıfırla") { _, _ ->
                    StoryMemory(getSharedPreferences("hafiza", MODE_PRIVATE)).reset()
                    showMem()
                }
                .setNegativeButton("Vazgeç", null)
                .show()
        }
    }

    private fun bindSwitch(id: Int, key: String, def: Boolean) {
        val s = findViewById<MaterialSwitch>(id)
        s.isChecked = prefs.getBoolean(key, def)
        s.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply() }
    }

    private fun fillProvider() {
        if (claudeSelected) {
            aiKeyLayout.hint = "Anthropic API anahtarı (sk-ant-…)"
            aiKey.setText(prefs.getString(K.CLAUDE_KEY, ""))
            aiModel.setText(prefs.getString(K.CLAUDE_MODEL, K.DEFAULT_CLAUDE))
            aiHelp.text = "Ücretli. Anahtar: console.anthropic.com\nclaude-opus-5-5 en iyi çeviriyi yapar; claude-haiku-5-5 hızlı ve çok ucuz."
        } else {
            aiKeyLayout.hint = "Gemini API anahtarı (AIza…)"
            aiKey.setText(prefs.getString(K.GEMINI_KEY, ""))
            aiModel.setText(prefs.getString(K.GEMINI_MODEL, K.DEFAULT_GEMINI))
            aiHelp.text = "Ücretsiz anahtar: aistudio.google.com/apikey\n${K.DEFAULT_GEMINI} hızlıdır ve günlük sınırı yüksektir; gemini-flash-latest daha iyi çevirir ama sınırı düşüktür."
        }
    }

    private fun saveAiFields() {
        val key = aiKey.text?.toString()?.trim().orEmpty()
        val model = aiModel.text?.toString()?.trim().orEmpty()
        val ed = prefs.edit()
        if (claudeSelected) {
            ed.putString(K.CLAUDE_KEY, key).putString(K.CLAUDE_MODEL, model.ifEmpty { K.DEFAULT_CLAUDE })
        } else {
            ed.putString(K.GEMINI_KEY, key).putString(K.GEMINI_MODEL, model.ifEmpty { K.DEFAULT_GEMINI })
        }
        ed.apply()
    }

    private fun save() {
        saveAiFields()
        prefs.edit()
            .putString(K.DEEPL, deepl.text?.toString()?.trim().orEmpty())
            .putBoolean(K.AI_ON, aiOn.isChecked)
            .putString(K.PROVIDER, if (claudeSelected) "claude" else "gemini")
            .apply()
    }

    override fun onPause() {
        super.onPause()
        save()
    }
}
