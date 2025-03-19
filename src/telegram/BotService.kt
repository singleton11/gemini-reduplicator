package telegram

import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.message
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.logging.LogLevel
import config.AppConfig
import gemini.GeminiClient
import kotlinx.coroutines.runBlocking

class BotService(
    private val geminiClient: GeminiClient,
    private val telegramService: TelegramService
) {
    private val botToken = AppConfig.telegramToken
    
    fun startBot() {
        val bot = bot {
            logLevel = LogLevel.All()
            token = botToken
            dispatch {
                message {
                    runBlocking {
                        // Handle text messages
                        if (message.text != null) {
                            val huefied = geminiClient.generateTextResponse(message.text!!)
                            if (huefied != null) {
                                bot.sendMessage(ChatId.fromId(message.chat.id), huefied, replyToMessageId = message.messageId)
                            }
                        }
                        // Handle image messages
                        else if (message.photo != null) {
                            val fileInfo = telegramService.downloadImage(message)
                            if (fileInfo != null) {
                                val huefied = geminiClient.generateImageResponse(fileInfo.bytes, fileInfo.mimeType)
                                if (huefied != null) {
                                    bot.sendMessage(ChatId.fromId(message.chat.id), huefied, replyToMessageId = message.messageId)
                                }
                            }
                        }
                        // Handle sticker messages
                        else if (message.sticker != null) {
                            val fileInfo = telegramService.downloadSticker(message)
                            if (fileInfo != null) {
                                val huefied = geminiClient.generateImageResponse(fileInfo.bytes, fileInfo.mimeType)
                                if (huefied != null) {
                                    bot.sendMessage(ChatId.fromId(message.chat.id), huefied, replyToMessageId = message.messageId)
                                }
                            }
                        }
                    }
                }
            }
        }
        
        AppConfig.logger.info("Starting Telegram bot...")
        bot.startPolling()
    }
}
