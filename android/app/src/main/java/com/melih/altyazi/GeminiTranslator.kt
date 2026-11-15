package com.melih.altyazi

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ücretsiz seçenek: Google Gemini API (aistudio.google.com'dan alınan anahtar).
 * Ücretsiz katmanın dakikalık istek sınırı var; aşmamak için istekler aralıklı gönderilir,
 * sınır aşılırsa bir süre beklenir ve o arada hızlı çeviri ekranda kalır.
 */
class GeminiTranslator(private val apiKey: String, private val model: String) : AiTranslator() {

    private var lastCall = 0L
    private var blockedUntil = 0L
    private val minGapMs = 4500L

    override fun complete(system: String, user: String, json: Boolean): String? {
        val now = SystemClock.elapsedRealtime()
        if (now < blockedUntil) return null
        val wait = lastCall + minGapMs - now
        if (wait > 0) Thread.sleep(wait)
        lastCall = SystemClock.elapsedRealtime()

        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(
                JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user)))
            ))
            .put("generationConfig", JSONObject().apply {
                put("temperature", 0.3)
                if (json) put("responseMimeType", "application/json")
            })

        val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 8000
            c.readTimeout = 25000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", apiKey)
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = c.responseCode
            if (code == 429) {
                blockedUntil = SystemClock.elapsedRealtime() + 30_000
                lastProblem = "Gemini ücretsiz sınırı doldu, 30 sn bekleniyor"
                return null
            }
            if (code == 400 || code == 403) { lastProblem = "Gemini anahtarı geçersiz ya da hatalı"; return null }
            if (code == 404) { lastProblem = "Gemini model adı bulunamadı: $model"; return null }
            if (code != 200) { lastProblem = "Gemini hata $code"; return null }

            val text = c.inputStream.bufferedReader().use { it.readText() }
            val parts = JSONObject(text).optJSONArray("candidates")
                ?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return null
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                if (p.optBoolean("thought", false)) continue
                sb.append(p.optString("text", ""))
            }
            lastProblem = null
            return sb.toString().trim().ifEmpty { null }
        } catch (e: Exception) {
            lastProblem = "Gemini bağlantı hatası"
            return null
        } finally {
            c.disconnect()
        }
    }
}
