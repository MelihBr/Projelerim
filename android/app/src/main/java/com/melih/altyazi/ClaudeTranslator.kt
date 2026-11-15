package com.melih.altyazi

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import org.json.JSONObject
import java.time.Duration

/** Hikâyeyi bilen çevirmen: özet + sözlük + son replikler ile Claude'a çevirtir. */
class ClaudeTranslator(apiKey: String, private val model: String) {

    data class Result(val tr: String, val enFixed: String, val notes: List<String>, val glossary: List<Pair<String, String>>)

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .timeout(Duration.ofSeconds(30))
        .maxRetries(1)
        .build()

    private val system = """
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

    private fun complete(sys: String, user: String, maxTokens: Long): String? {
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(maxTokens)
            .system(sys)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .addUserMessage(user)
            .build()
        val resp = client.messages().create(params)
        if (resp.stopReason().map { it == StopReason.REFUSAL }.orElse(false)) return null
        val text = resp.content().mapNotNull { b -> b.text().orElse(null)?.text() }.joinToString("").trim()
        return text.ifEmpty { null }
    }

    /** Hata ya da ret olursa null döner; çağıran taraf hızlı çeviriyi korur. */
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
        val raw = complete(system, user, 4000L) ?: return null
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val o = JSONObject(raw.substring(start, end + 1))
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
        return complete(sys, user, 4000L)
    }
}
