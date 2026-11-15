package com.melih.altyazi

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import java.time.Duration

/** Ücretli seçenek: Anthropic API. */
class ClaudeTranslator(apiKey: String, private val model: String) : AiTranslator() {

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .timeout(Duration.ofSeconds(30))
        .maxRetries(1)
        .build()

    override fun complete(system: String, user: String, json: Boolean): String? {
        return try {
            val params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(4000L)
                .system(system)
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .addUserMessage(user)
                .build()
            val resp = client.messages().create(params)
            if (resp.stopReason().map { it == StopReason.REFUSAL }.orElse(false)) return null
            lastProblem = null
            resp.content().mapNotNull { b -> b.text().orElse(null)?.text() }.joinToString("").trim().ifEmpty { null }
        } catch (e: Exception) {
            lastProblem = "Claude: ${e.javaClass.simpleName}"
            null
        }
    }
}
