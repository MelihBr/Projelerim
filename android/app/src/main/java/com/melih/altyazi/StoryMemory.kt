package com.melih.altyazi

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Oyun boyunca öğrenilen hikâye bilgisi: kısa özet, isim/terim sözlüğü ve son replikler.
 * Telefonda saklanır, uygulama kapanıp açılınca kaldığı yerden devam eder.
 */
class StoryMemory(private val prefs: SharedPreferences) {

    data class Line(val who: String, val en: String, val tr: String)

    var summary: String = ""
        private set
    private val glossary = LinkedHashMap<String, String>()
    private val recent = ArrayDeque<Line>()
    private var sinceSummary = 0
    private val pendingForSummary = ArrayList<Line>()

    init { load() }

    @Synchronized fun addLine(l: Line) {
        recent.addLast(l)
        while (recent.size > 24) recent.removeFirst()
        pendingForSummary.add(l)
        sinceSummary++
        save()
    }

    @Synchronized fun addGlossary(en: String, tr: String) {
        val k = en.trim()
        if (k.isEmpty() || k.length > 40 || tr.isBlank()) return
        glossary.remove(k)
        glossary[k] = tr.trim()
        while (glossary.size > 80) glossary.remove(glossary.keys.first())
        save()
    }

    /** Özet güncellenmeli mi? Güncellenecekse bekleyen replikleri döndürür. */
    @Synchronized fun takeLinesForSummary(every: Int = 20): List<Line>? {
        if (sinceSummary < every) return null
        sinceSummary = 0
        val out = ArrayList(pendingForSummary)
        pendingForSummary.clear()
        return out
    }

    @Synchronized fun setSummary(s: String) {
        summary = s.trim().take(1500)
        save()
    }

    @Synchronized fun reset() {
        summary = ""; glossary.clear(); recent.clear(); pendingForSummary.clear(); sinceSummary = 0
        save()
    }

    @Synchronized fun glossaryText(): String =
        if (glossary.isEmpty()) "(henüz yok)" else glossary.entries.joinToString("\n") { "${it.key} = ${it.value}" }

    @Synchronized fun recentText(): String =
        if (recent.isEmpty()) "(henüz yok)" else recent.joinToString("\n") { l ->
            (if (l.who.isNotEmpty()) "${l.who}: " else "") + l.en + "  =>  " + l.tr
        }

    @Synchronized fun stats(): String = "${glossary.size} terim, ${recent.size} son replik" +
        if (summary.isEmpty()) ", özet henüz yok" else ""

    private fun save() {
        val o = JSONObject()
        o.put("summary", summary)
        o.put("since", sinceSummary)
        o.put("glossary", JSONObject(glossary as Map<*, *>))
        o.put("recent", JSONArray(recent.map { JSONObject().put("w", it.who).put("e", it.en).put("t", it.tr) }))
        o.put("pending", JSONArray(pendingForSummary.map { JSONObject().put("w", it.who).put("e", it.en).put("t", it.tr) }))
        prefs.edit().putString("story", o.toString()).apply()
    }

    private fun load() {
        val raw = prefs.getString("story", null) ?: return
        try {
            val o = JSONObject(raw)
            summary = o.optString("summary", "")
            sinceSummary = o.optInt("since", 0)
            o.optJSONObject("glossary")?.let { g -> g.keys().forEach { k -> glossary[k] = g.optString(k) } }
            o.optJSONArray("recent")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { recent.addLast(Line(it.optString("w"), it.optString("e"), it.optString("t"))) } }
            o.optJSONArray("pending")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { pendingForSummary.add(Line(it.optString("w"), it.optString("e"), it.optString("t"))) } }
        } catch (e: Exception) {
            // bozuk kayıt: temiz başla
        }
    }
}
