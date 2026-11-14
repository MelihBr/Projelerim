package com.melih.altyazi

import kotlin.math.max
import kotlin.math.min

object TextUtil {
    private val bad = Regex("[^A-Za-z0-9'\",.?!:;…&%\\- ]")
    private val spaces = Regex("\\s+")
    private val word = Regex("[A-Za-z]{2,}")
    private val oneWord = Regex("^[A-Z][a-z]+[.!?]$")
    private val speaker = Regex("^([A-Z][A-Za-z.' ]{1,20}):\\s*(.+)$")

    /** OCR satırını temizler; çöp gibi görünüyorsa boş döner. */
    fun clean(raw: String): String {
        var s = raw.replace('|', 'I')
            .replace('“', '"').replace('”', '"')
            .replace('‘', '\'').replace('’', '\'').replace('`', '\'')
        s = bad.replace(s, " ").replace(spaces, " ").trim()
        if (s.length < 3) return ""
        val letters = s.count { it.isLetter() }
        if (letters.toFloat() / s.length < 0.6f) return ""
        val words = s.split(" ").count { word.containsMatchIn(it) }
        if (words < 2 && !oneWord.matches(s)) return ""
        return s
    }

    private fun norm(s: String) = s.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(220)

    /** 0..1 arası benzerlik (Levenshtein). */
    fun similarity(x: String, y: String): Double {
        val a = norm(x)
        val b = norm(y)
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost)
            }
            prev = cur
        }
        return 1.0 - prev[b.length].toDouble() / max(a.length, b.length)
    }

    /** "Lucia: metin" -> ("Lucia", "metin") */
    fun splitSpeaker(text: String): Pair<String, String> {
        val m = speaker.find(text) ?: return "" to text
        return m.groupValues[1].trim() to m.groupValues[2].trim()
    }
}
