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

@Resource("/v1beta/models/gemini-1.5-flash:generateContent")
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
        }
        
        return try {
            val resp = response.body<GeminiResponse>()
            resp.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text
        } catch (e: JsonConvertException) {
            AppConfig.logger.error("Error processing text with Gemini: ${e.message}", e)
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
}
