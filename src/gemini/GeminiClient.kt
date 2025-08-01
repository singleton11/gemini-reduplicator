package gemini

import config.AppConfig
import io.ktor.client.call.*
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.resources.post
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.serialization.*
import kotlinx.serialization.Serializable
import java.util.Base64
import com.github.kotlintelegrambot.entities.Message
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.timeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

@Resource("/v1beta/models/gemini-2.5-flash:generateContent")
data class GeminiRequestResource(val key: String = AppConfig.geminiApiKey)

@Serializable
data class GeminiContent(val parts: List<GeminiPart>) {
    @Serializable
    data class GeminiPart(
        val text: String? = null,
        val inlineData: InlineData? = null
    )

    @Serializable
    data class InlineData(
        val mimeType: String,
        val data: String
    )
}

@Serializable
data class GeminiRequest(val contents: List<GeminiContent>)

@Serializable
data class GeminiResponse(val candidates: List<GeminiCandidate>) {
    @Serializable
    data class GeminiCandidate(val content: GeminiContent)
}

class GeminiClient {
    companion object {
        private const val MAX_BATCH_IMAGES = 3 // Limit batch processing to 3 images max (Gemini has token limits)
    }

    private val httpClient = AppConfig.httpClient.config {
        defaultRequest {
            url("https://generativelanguage.googleapis.com/")
        }
    }

    private val promptTemplate = GeminiClient::class.java
        .getResourceAsStream("/prompt-template.txt")
        ?.bufferedReader()
        ?.readText()

    private val imagePromptTemplate = GeminiClient::class.java
        .getResourceAsStream("/image-prompt-template.txt")
        ?.bufferedReader()
        ?.readText()

    private val batchPromptTemplate = GeminiClient::class.java
        .getResourceAsStream("/batch-prompt-template.txt")
        ?.bufferedReader()
        ?.readText()

    private val batchImagePromptTemplate = GeminiClient::class.java
        .getResourceAsStream("/batch-image-prompt-template.txt")
        ?.bufferedReader()
        ?.readText()

    suspend fun generateTextResponse(phrase: String): String? {
        val prompt = promptTemplate?.replace("{{ placeholder }}", phrase) ?: error("Cannot build prompt")

        val response = httpClient.post(GeminiRequestResource()) {
            contentType(ContentType.Application.Json)
            setBody(
                GeminiRequest(
                    listOf(
                        GeminiContent(
                            listOf(
                                GeminiContent.GeminiPart(text = prompt)
                            )
                        )
                    )
                )
            )
            timeout {
                requestTimeoutMillis = 10.seconds.inWholeMilliseconds
            }
        }

        return try {
            val resp = response.body<GeminiResponse>()
            resp.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text
        } catch (e: JsonConvertException) {
            AppConfig.logger.error("Error processing text with Gemini: ${e.message}", e)
            null
        } catch (e: HttpRequestTimeoutException) {
            AppConfig.logger.warn("Gemini request timed out: ${e.message}")
            null
        } catch (e: Exception) {
            AppConfig.logger.error("Unexpected error processing text with Gemini: ${e.message}", e)
            null
        }
    }

    suspend fun generateImageResponse(imageBytes: ByteArray, mimeType: String = "image/jpeg"): String? {
        val prompt = imagePromptTemplate ?: error("Cannot build image prompt")
        val base64Image = Base64.getEncoder().encodeToString(imageBytes)

        AppConfig.logger.info("Processing image of size ${imageBytes.size} bytes with MIME type: $mimeType")

        val response = httpClient.post(GeminiRequestResource()) {
            contentType(ContentType.Application.Json)
            setBody(
                GeminiRequest(
                    listOf(
                        GeminiContent(
                            listOf(
                                GeminiContent.GeminiPart(
                                    inlineData = GeminiContent.InlineData(
                                        mimeType = mimeType,
                                        data = base64Image
                                    )
                                ),
                                GeminiContent.GeminiPart(text = prompt)
                            )
                        )
                    )
                )
            )
        }

        return try {
            val resp = response.body<GeminiResponse>()
            resp.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text
        } catch (e: Exception) {
            AppConfig.logger.error("Error processing image with Gemini: ${e.message}", e)
            null
        }
    }

    /**
     * Processes a batch of text messages and generates a summary response
     */
    suspend fun generateBatchTextResponse(messages: List<String>): String? {
        if (messages.isEmpty()) return null

        // If only one message, use the regular processing
        if (messages.size == 1) return generateTextResponse(messages.first())

        val messagesText = messages.joinToString("\n\n") { "- $it" }
        val prompt = batchPromptTemplate?.replace("{{ placeholder }}", messagesText)
            ?: error("Cannot build batch prompt")

        AppConfig.logger.info("Processing batch of ${messages.size} messages")

        val response = httpClient.post(GeminiRequestResource()) {
            contentType(ContentType.Application.Json)
            setBody(
                GeminiRequest(
                    listOf(
                        GeminiContent(
                            listOf(
                                GeminiContent.GeminiPart(text = prompt)
                            )
                        )
                    )
                )
            )
        }

        return try {
            val resp = response.body<GeminiResponse>()
            resp.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text
        } catch (e: Exception) {
            AppConfig.logger.error("Error processing batch text with Gemini: ${e.message}", e)
            null
        }
    }

    /**
     * Extract text content from a list of Telegram messages
     */
    private fun extractTextContent(messages: List<Message>): List<String> {
        return messages.mapNotNull { it.text }.filter { it.isNotBlank() }
    }

    /**
     * Process a batch of mixed Telegram messages, including text and images
     */
    suspend fun processBatchMessages(messages: List<Message>, telegramService: telegram.TelegramService): String? {
        if (messages.isEmpty()) return null

        // If there's only one message, use the regular processing
        if (messages.size == 1) {
            val message = messages.first()
            return when {
                message.text != null -> generateTextResponse(message.text!!)
                message.photo != null -> {
                    val fileInfo = telegramService.downloadImage(message)
                    fileInfo?.let { generateImageResponse(it.bytes, it.mimeType) }
                }

                message.sticker != null -> {
                    val fileInfo = telegramService.downloadSticker(message)
                    fileInfo?.let { generateImageResponse(it.bytes, it.mimeType) }
                }

                else -> null
            }
        }

        // Extract text content
        val textContents = extractTextContent(messages)

        // If we only have text messages, use the batch text processor
        if (textContents.size == messages.size) {
            return generateBatchTextResponse(textContents)
        }

        // We have a mix of content types - handle differently
        val parts = mutableListOf<GeminiContent.GeminiPart>()
        var imageCount = 0

        // First add images (limited to MAX_BATCH_IMAGES)
        for (message in messages) {
            if (imageCount >= MAX_BATCH_IMAGES) break

            val fileInfo = when {
                message.photo != null -> telegramService.downloadImage(message)
                message.sticker != null -> telegramService.downloadSticker(message)
                else -> null
            }

            if (fileInfo != null) {
                imageCount++
                AppConfig.logger.info("Adding image ${imageCount} to batch")
                parts.add(
                    GeminiContent.GeminiPart(
                        inlineData = GeminiContent.InlineData(
                            mimeType = fileInfo.mimeType,
                            data = Base64.getEncoder().encodeToString(fileInfo.bytes)
                        )
                    )
                )
            }
        }

        // Then add text content and prompt
        val textDescription = if (textContents.isNotEmpty()) {
            "\nThese messages were also included: \n" + textContents.joinToString("\n\n") { "- $it" }
        } else {
            ""
        }

        val promptText = batchImagePromptTemplate?.replace("{{ text_content }}", textDescription)
            ?: error("Cannot build batch image prompt")

        parts.add(GeminiContent.GeminiPart(text = promptText))

        AppConfig.logger.info("Processing batch with ${parts.size - 1} images and ${textContents.size} text messages")

        val response = httpClient.post(GeminiRequestResource()) {
            contentType(ContentType.Application.Json)
            setBody(
                GeminiRequest(
                    listOf(
                        GeminiContent(parts)
                    )
                )
            )
        }

        return try {
            val resp = response.body<GeminiResponse>()
            resp.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text
        } catch (e: Exception) {
            AppConfig.logger.error("Error processing batch messages with Gemini: ${e.message}", e)
            null
        }
    }
}
