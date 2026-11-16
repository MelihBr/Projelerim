package com.melih.altyazi

import android.os.SystemClock
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Hızlı çeviri katmanı. Sıra: DeepL (anahtar varsa) -> Google Çeviri -> telefondaki çevrimdışı model.
 * Geri çağrı (çeviri, motor adı) ile döner; arka plan iş parçacığından gelebilir.
 */
class SubtitleTranslator {
    private val exec = Executors.newSingleThreadExecutor()
    private val cache = object : LinkedHashMap<String, Pair<String, String>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, String>>?) = size > 400
    }
    private val offline = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.TURKISH)
            .build()
    )

    @Volatile var offlineReady = false
        private set

    /** DeepL API anahtarı; boşsa DeepL atlanır. */
    @Volatile var deeplKey: String = ""

    /** DeepL ile ilgili son sorun (kota doldu, anahtar hatalı...). Sorun yoksa null. */
    @Volatile var deeplProblem: String? = null
        private set
    @Volatile private var deeplBlockedUntil = 0L

    fun prepareOffline(done: (Boolean) -> Unit) {
        offline.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { offlineReady = true; done(true) }
            .addOnFailureListener { done(false) }
    }

    /**
     * @param context önceki birkaç İngilizce replik; DeepL'e bağlam olarak verilir (kotadan düşmez).
     */
    fun translate(text: String, context: String, cb: (String?, String) -> Unit) {
        synchronized(cache) { cache[text] }?.let { cb(it.first, it.second); return }
        exec.execute {
            val key = deeplKey
            if (key.isNotEmpty() && SystemClock.elapsedRealtime() >= deeplBlockedUntil) {
                val d = try { deepl(key, text, context) } catch (e: Exception) { deeplProblem = "DeepL bağlantı hatası"; null }
                if (d != null) { store(text, d, "DeepL"); cb(d, "DeepL"); return@execute }
            }
            val g = try { google(text) } catch (e: Exception) { null }
            if (g != null) { store(text, g, "Google"); cb(g, "Google"); return@execute }
            if (!offlineReady) { cb(null, ""); return@execute }
            offline.translate(text)
                .addOnSuccessListener { r -> store(text, r, "çevrimdışı"); cb(r, "çevrimdışı") }
                .addOnFailureListener { cb(null, "") }
        }
    }

    private fun store(text: String, tr: String, engine: String) {
        synchronized(cache) { cache[text] = tr to engine }
    }

    private fun deepl(key: String, text: String, context: String): String? {
        // Ücretsiz plan anahtarlarının sonu ":fx" ile biter ve ayrı adrese gider.
        val host = if (key.endsWith(":fx")) "api-free.deepl.com" else "api.deepl.com"
        val body = JSONObject()
            .put("text", JSONArray().put(text))
            .put("source_lang", "EN")
            .put("target_lang", "TR")
        if (context.isNotBlank()) body.put("context", context)

        val c = URL("https://$host/v2/translate").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 4000
            c.readTimeout = 5000
            c.doOutput = true
            c.setRequestProperty("Authorization", "DeepL-Auth-Key $key")
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            when (val code = c.responseCode) {
                200 -> {}
                456 -> {
                    deeplProblem = "DeepL aylık ücretsiz kotası doldu, Google kullanılıyor"
                    deeplBlockedUntil = SystemClock.elapsedRealtime() + 60 * 60_000L
                    return null
                }
                403 -> {
                    deeplProblem = "DeepL anahtarı geçersiz"
                    deeplBlockedUntil = SystemClock.elapsedRealtime() + 10 * 60_000L
                    return null
                }
                429 -> {
                    deeplProblem = "DeepL çok sık istek, kısa süre Google kullanılıyor"
                    deeplBlockedUntil = SystemClock.elapsedRealtime() + 10_000L
                    return null
                }
                else -> { deeplProblem = "DeepL hata $code"; return null }
            }
            val resp = c.inputStream.bufferedReader().use { it.readText() }
            val out = JSONObject(resp).optJSONArray("translations")?.optJSONObject(0)?.optString("text")?.trim()
            deeplProblem = null
            return out?.ifEmpty { null }
        } finally {
            c.disconnect()
        }
    }

    private fun google(text: String): String? {
        val url = URL(
            "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=tr&dt=t&q=" +
                URLEncoder.encode(text, "UTF-8")
        )
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 4000
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        try {
            if (c.responseCode != 200) return null
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val segs = JSONArray(body).getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until segs.length()) {
                val seg = segs.optJSONArray(i) ?: continue
                sb.append(seg.optString(0, ""))
            }
            return sb.toString().trim().ifEmpty { null }
        } finally {
            c.disconnect()
        }
    }

    fun close() {
        offline.close()
        exec.shutdown()
    }
}
