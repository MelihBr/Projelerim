package com.melih.altyazi

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Önce Google Çeviri (internet, daha iyi kalite), olmazsa telefondaki çevrimdışı ML Kit modeli.
 * Geri çağrılar arka plan iş parçacığından gelebilir.
 */
class SubtitleTranslator {
    private val exec = Executors.newSingleThreadExecutor()
    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 400
    }
    private val offline = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.TURKISH)
            .build()
    )

    @Volatile var offlineReady = false
        private set

    fun prepareOffline(done: (Boolean) -> Unit) {
        offline.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { offlineReady = true; done(true) }
            .addOnFailureListener { done(false) }
    }

    fun translate(text: String, cb: (String?) -> Unit) {
        synchronized(cache) { cache[text] }?.let { cb(it); return }
        exec.execute {
            val online = try { google(text) } catch (e: Exception) { null }
            if (online != null) {
                synchronized(cache) { cache[text] = online }
                cb(online)
                return@execute
            }
            if (!offlineReady) { cb(null); return@execute }
            offline.translate(text)
                .addOnSuccessListener { r -> synchronized(cache) { cache[text] = r }; cb(r) }
                .addOnFailureListener { cb(null) }
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
