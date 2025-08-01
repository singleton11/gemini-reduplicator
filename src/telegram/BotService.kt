package telegram

import com.github.kotlintelegrambot.Bot
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlin.time.Duration.Companion.seconds

class BotService(
    private val geminiClient: GeminiClient,
    private val telegramService: TelegramService,
    private val throttleTimeMs: Long = 10.seconds.inWholeMilliseconds
) {
    private val processingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val botToken = AppConfig.telegramToken
    private val messageBuffer = Channel<Message>(1024)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun startBot() {
        val bot = bot {
            logLevel = LogLevel.All()
            token = botToken
            dispatch {
                message {
                    coroutineScope {
                        launch {
                            messageBuffer.send(message)
                        }
                    }
                }
            }
        }

        AppConfig.logger.info("Starting Telegram bot...")

        processingScope.launch {
            while (true) {
                delay(throttleTimeMs)
                AppConfig.logger.debug("Processing message buffer...")
                val messagesToProcess = buildList {
                    while (!messageBuffer.isEmpty) {
                        messageBuffer.tryReceive().getOrNull()?.let {
                            add(it)
                        }
                    }
                }

                AppConfig.logger.debug("Processing ${messagesToProcess.size} messages...")

                messagesToProcess.groupBy { it.chat.id }.forEach { (chatId, messages) ->
                    launch {
                        processMessagesBatch(bot, messages, chatId)
                    }
                }
            }
        }

        bot.startPolling()
    }

    /**
     * Process a batch of messages for a specific chat
     */
    private suspend fun processMessagesBatch(bot: Bot, messages: List<Message>, chatId: Long) {
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
