import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.message
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.Message
import com.github.kotlintelegrambot.entities.TelegramFile
import com.github.kotlintelegrambot.logging.LogLevel
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.plugins.resources.Resources
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.serialization.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64

val geminiApiKey = System.getenv("GEMINI_API_KEY")

val httpClient = HttpClient(CIO) {
    install(Resources)
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }
    install(Logging)

    defaultRequest { url("https://generativelanguage.googleapis.com/") }
}

@Resource("/v1beta/models/gemini-1.5-flash:generateContent")
data class GeminiRequestResource(val key: String = geminiApiKey)

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

val promptTemplate = object {}
    .javaClass
    .getResource("prompt-template.txt")
    ?.readText()

val imagePromptTemplate = object {}
    .javaClass
    .getResource("image-prompt-template.txt")
    ?.readText()

suspend fun huefy(phrase: String): String? {
    val prompt = promptTemplate?.replace("{{ placeholder }}", phrase) ?: error("Can not build prompt")
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
    } catch (_: JsonConvertException) {
        null
    }
}

suspend fun huefyImage(imageBytes: ByteArray, mimeType: String = "image/jpeg"): String? {
    val prompt = imagePromptTemplate ?: error("Can not build image prompt")
    val base64Image = Base64.getEncoder().encodeToString(imageBytes)

    println("Processing image of size ${imageBytes.size} bytes with MIME type: $mimeType")

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
        println("Error processing image with Gemini: ${e.message}")
        e.printStackTrace()
        null
    }
}

data class FileInfo(val bytes: ByteArray, val mimeType: String) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FileInfo

        if (!bytes.contentEquals(other.bytes)) return false
        if (mimeType != other.mimeType) return false

        return true
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + mimeType.hashCode()
        return result
    }
}

suspend fun downloadFileById(fileId: String, fileType: String): FileInfo? {
    try {
        val telegramToken = System.getenv("TELEGRAM_TOKEN")

        // Get file path
        val getFileUrl = "https://api.telegram.org/bot$telegramToken/getFile?file_id=$fileId"
        val getFileResponse = httpClient.get(getFileUrl)
        val filePathJson = Json.parseToJsonElement(getFileResponse.bodyAsText()) as? JsonObject
        val filePath = (filePathJson?.get("result") as? JsonObject)?.get("file_path") as? JsonPrimitive

        // Download file
        if (filePath != null) {
            val path = filePath.content
            println("Downloading $fileType from path: $path")
            val fileUrl = "https://api.telegram.org/file/bot$telegramToken/$path"
            val fileResponse = httpClient.get(fileUrl)
            val bytes = fileResponse.readBytes()
            println("Downloaded ${bytes.size} bytes")

            // Determine MIME type based on file extension
            val mimeType = when {
                path.endsWith(".jpg", ignoreCase = true) || path.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
                path.endsWith(".png", ignoreCase = true) -> "image/png"
                path.endsWith(".gif", ignoreCase = true) -> "image/gif"
                path.endsWith(".webp", ignoreCase = true) -> "image/webp"
                path.endsWith(".tgs", ignoreCase = true) -> "application/x-tgsticker" // Telegram animated sticker
                else -> "image/jpeg" // Default
            }

            println("Detected MIME type: $mimeType")
            return FileInfo(bytes, mimeType)
        } else {
            println("Could not get file path from Telegram API response for $fileType")
        }
    } catch (e: Exception) {
        println("Error downloading $fileType: ${e.message}")
        e.printStackTrace()
    }
    return null
}

suspend fun downloadImage(message: Message): FileInfo? {
    val photoSize = message.photo?.maxByOrNull { it.width * it.height }
    return if (photoSize != null) {
        println("Found photo with dimensions: ${photoSize.width}x${photoSize.height}")
        downloadFileById(photoSize.fileId, "photo")
    } else {
        println("No photo found in message")
        null
    }
}

suspend fun downloadSticker(message: Message): FileInfo? {
    val sticker = message.sticker
    return if (sticker != null) {
        println("Found sticker: ${sticker.emoji ?: ""} (${sticker.width}x${sticker.height})")
        downloadFileById(sticker.fileId, "sticker")
    } else {
        println("No sticker found in message")
        null
    }
}

fun main(args: Array<String>) {
    val bot = bot {
        logLevel = LogLevel.All()
        token = System.getenv("TELEGRAM_TOKEN")
        dispatch {
            message {
                runBlocking {
                    // Handle text messages
                    if (message.text != null) {
                        val huefied = huefy(message.text!!)
                        if (huefied != null) {
                            bot.sendMessage(
                                ChatId.fromId(message.chat.id),
                                huefied,
                                replyToMessageId = message.messageId
                            )
                        }
                    }
                    // Handle image messages
                    else if (message.photo != null) {
                        val fileInfo = downloadImage(message)
                        if (fileInfo != null) {
                            val huefied = huefyImage(fileInfo.bytes, fileInfo.mimeType)
                            if (huefied != null) {
                                bot.sendMessage(
                                    ChatId.fromId(message.chat.id),
                                    huefied,
                                    replyToMessageId = message.messageId
                                )
                            }
                        }
                    }
                    // Handle sticker messages
                    else if (message.sticker != null) {
                        val fileInfo = downloadSticker(message)
                        if (fileInfo != null) {
                            val huefied = huefyImage(fileInfo.bytes, fileInfo.mimeType)
                            if (huefied != null) {
                                bot.sendMessage(
                                    ChatId.fromId(message.chat.id),
                                    huefied,
                                    replyToMessageId = message.messageId
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    bot.startPolling()
}
