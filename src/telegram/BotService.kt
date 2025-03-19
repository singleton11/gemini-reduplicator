package telegram

import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.message
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.Message
import com.github.kotlintelegrambot.logging.LogLevel
import config.AppConfig
import gemini.GeminiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import utils.MessageBuffer

class BotService(
    private val geminiClient: GeminiClient,
    private val telegramService: TelegramService,
    private val throttleTimeMs: Long = 5000 // Default 5 seconds throttling
) {
    private val messageBuffer = MessageBuffer(throttleTimeMs)
    private val processingScope = CoroutineScope(Dispatchers.IO)
    private val botToken = AppConfig.telegramToken
    
    fun startBot() {
        val bot = bot {
            logLevel = LogLevel.All()
            token = botToken
            dispatch {
                message {
                    // Launch in a coroutine to avoid blocking the bot
                    processingScope.launch {
                        val chatId = message.chat.id
                        
                        // Buffer the message and check if we should process now
                        if (messageBuffer.bufferMessage(chatId, message)) {
                            processMessageBatch(bot, chatId)
                        }
                    }
                }
            }
        }
        
        AppConfig.logger.info("Starting Telegram bot...")
        bot.startPolling()
    }
    
    /**
     * Process a batch of messages for a specific chat
     */
    private suspend fun processMessageBatch(bot: com.github.kotlintelegrambot.Bot, chatId: Long) {
        // Get all buffered messages
        val messages = messageBuffer.getAndClearMessages(chatId)
        
        if (messages.isEmpty()) {
            AppConfig.logger.debug("No messages to process for chat $chatId")
            return
        }
        
        // Log how many messages we're processing
        AppConfig.logger.info("Processing batch of ${messages.size} messages for chat $chatId")
        
        // Process the batch
        val response = geminiClient.processBatchMessages(messages, telegramService)
        
        // Send the response
        if (response != null) {
            // Reply to the last message in the batch
            val replyToMessageId = messages.lastOrNull()?.messageId
            bot.sendMessage(
                ChatId.fromId(chatId),
                response,
                replyToMessageId = replyToMessageId
            )
        } else {
            AppConfig.logger.warn("Failed to get response from Gemini for chat $chatId batch")
        }
    }
}
