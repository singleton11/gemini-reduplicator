package telegram

import config.AppConfig
import io.ktor.client.request.*
import io.ktor.client.statement.*
import com.github.kotlintelegrambot.entities.Message
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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

class TelegramService {
    private val httpClient = AppConfig.httpClient
    private val telegramToken = AppConfig.telegramToken
    
    suspend fun downloadFileById(fileId: String, fileType: String): FileInfo? {
        try {
            // Get file path
            val getFileUrl = "https://api.telegram.org/bot$telegramToken/getFile?file_id=$fileId"
            val getFileResponse = httpClient.get(getFileUrl)
            val filePathJson = Json.parseToJsonElement(getFileResponse.bodyAsText()) as? JsonObject
            val filePath = (filePathJson?.get("result") as? JsonObject)?.get("file_path") as? JsonPrimitive
            
            // Download file
            if (filePath != null) {
                val path = filePath.content
                AppConfig.logger.info("Downloading $fileType from path: $path")
                val fileUrl = "https://api.telegram.org/file/bot$telegramToken/$path"
                val fileResponse = httpClient.get(fileUrl)
                val bytes = fileResponse.readBytes()
                AppConfig.logger.info("Downloaded ${bytes.size} bytes")
                
                // Determine MIME type based on file extension
                val mimeType = when {
                    path.endsWith(".jpg", ignoreCase = true) || path.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
                    path.endsWith(".png", ignoreCase = true) -> "image/png"
                    path.endsWith(".gif", ignoreCase = true) -> "image/gif"
                    path.endsWith(".webp", ignoreCase = true) -> "image/webp"
                    path.endsWith(".tgs", ignoreCase = true) -> "application/x-tgsticker" // Telegram animated sticker
                    else -> "image/jpeg" // Default
                }
                
                AppConfig.logger.info("Detected MIME type: $mimeType")
                return FileInfo(bytes, mimeType)
            } else {
                AppConfig.logger.warn("Could not get file path from Telegram API response for $fileType")
            }
        } catch (e: Exception) {
            AppConfig.logger.error("Error downloading $fileType: ${e.message}", e)
        }
        return null
    }
    
    suspend fun downloadImage(message: Message): FileInfo? {
        val photoSize = message.photo?.maxByOrNull { it.width * it.height }
        return if (photoSize != null) {
            AppConfig.logger.info("Found photo with dimensions: ${photoSize.width}x${photoSize.height}")
            downloadFileById(photoSize.fileId, "photo")
        } else {
            AppConfig.logger.warn("No photo found in message")
            null
        }
    }
    
    suspend fun downloadSticker(message: Message): FileInfo? {
        val sticker = message.sticker
        return if (sticker != null) {
            AppConfig.logger.info("Found sticker: ${sticker.emoji ?: ""} (${sticker.width}x${sticker.height})")
            downloadFileById(sticker.fileId, "sticker")
        } else {
            AppConfig.logger.warn("No sticker found in message")
            null
        }
    }
}
