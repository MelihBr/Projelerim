package com.melih.altyazi

import org.json.JSONObject

/**
 * Hikâyeyi bilen çevirmenlerin ortak kısmı: istem metinleri ve cevabın ayrıştırılması.
 * Sağlayıcılar (Gemini, Claude) yalnızca complete() işini yapar.
 */
abstract class AiTranslator {

    data class Result(val tr: String, val enFixed: String, val notes: List<String>, val glossary: List<Pair<String, String>>)

    /** Sağlayıcıya tek bir istek gönderir; hata, ret ya da kota durumunda null döner. */
    protected abstract fun complete(system: String, user: String, json: Boolean): String?

    /** Son istek kota yüzünden reddedildiyse kullanıcıya gösterilecek kısa not. */
    @Volatile var lastProblem: String? = null
        protected set

    protected val translateSystem = """
You are the live Turkish subtitle translator for a player of Grand Theft Auto VI.
Setting: the state of Leonida (a parody of Florida) and Vice City (a parody of Miami). The two protagonists are Lucia Caminos and Jason Duval, a criminal couple. Expect heavy street slang, crime jargon, Florida/Miami culture, sarcasm and swearing.

The English line comes from OCR of a phone camera pointed at a monitor, so it can contain recognition errors (wrong letters, merged or split words, a stray HUD word). Silently fix obvious OCR mistakes using context.

Translate into natural spoken Turkish the way a professional Turkish subtitler would:
- meaning and tone over word-for-word; short and readable; keep the swearing strength;
- keep character names, place names and brands unchanged unless the glossary says otherwise;
- use the story summary, glossary and recent dialogue to resolve who is speaking, pronouns, references, running jokes and slang, and stay consistent with earlier translations.

Reply with ONLY one JSON object, no other text:
{"tr": "Turkish subtitle", "en_fixed": "the English line after OCR fixes", "notes": ["term = short Turkish explanation"], "glossary_add": [{"en": "name or recurring term", "tr": "how to render it in Turkish"}]}
"notes": at most 2, only for slang, idioms or cultural references in THIS line that a Turkish player would likely miss; otherwise [].
"glossary_add": only new character names, nicknames, gangs, places or recurring terms that are not in the glossary yet; otherwise [].
""".trimIndent()

    fun translate(who: String, en: String, quickTr: String, memory: StoryMemory): Result? {
        val user = buildString {
            append("STORY SO FAR (Turkish summary):\n").append(memory.summary.ifEmpty { "(nothing yet)" }).append("\n\n")
            append("GLOSSARY (English = Turkish):\n").append(memory.glossaryText()).append("\n\n")
            append("RECENT DIALOGUE (oldest first, English => Turkish):\n").append(memory.recentText()).append("\n\n")
            append("NEW LINE TO TRANSLATE\n")
            append("speaker: ").append(who.ifEmpty { "(unknown)" }).append("\n")
            append("english (OCR): ").append(en).append("\n")
            append("quick machine translation (may be wrong): ").append(quickTr)
        }
        val raw = complete(translateSystem, user, json = true) ?: return null
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val o = try { JSONObject(raw.substring(start, end + 1)) } catch (e: Exception) { return null }
        val tr = o.optString("tr").trim()
        if (tr.isEmpty()) return null
        val notes = o.optJSONArray("notes")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.take(2) } ?: emptyList()
        val gl = o.optJSONArray("glossary_add")?.let { a ->
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { it.optString("en") to it.optString("tr") } }
        } ?: emptyList()
        return Result(tr, o.optString("en_fixed").trim().ifEmpty { en }, notes, gl)
    }

    fun updateSummary(old: String, lines: List<StoryMemory.Line>): String? {
        val sys = "You keep a running Turkish summary of the story of a Grand Theft Auto VI play-through, used as context for a live subtitle translator."
        val user = buildString {
            append("Current summary (Turkish):\n").append(old.ifEmpty { "(empty)" }).append("\n\n")
            append("New dialogue since then:\n")
            lines.forEach { l -> append(if (l.who.isNotEmpty()) "${l.who}: " else "").append(l.en).append("\n") }
            append("\nWrite the updated summary in Turkish, at most 150 words: who the characters are and how they relate, the current goal or mission, and the important events so far. Reply with only the summary text.")
        }
        return complete(sys, user, json = false)
    }
}
