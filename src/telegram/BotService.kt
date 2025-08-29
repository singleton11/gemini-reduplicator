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
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.time.Duration.Companion.seconds

class BotService(
    private val geminiClient: GeminiClient,
    private val telegramService: TelegramService,
    private val throttleTimeMs: Long = 10.seconds.inWholeMilliseconds
) {
    private val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
                try {
                    AppConfig.logger.debug("Processing message buffer...")
                    val messagesToProcess = buildList {
                        val job = launch {
                            while (isActive) {
                                add(messageBuffer.receive())
                            }
                        }

                        delay(throttleTimeMs)
                        job.cancel()
                    }

                    AppConfig.logger.debug("Processing ${messagesToProcess.size} messages...")

                    messagesToProcess.groupBy { it.chat.id }.forEach { (chatId, messages) ->
                        launch {
                            processMessagesBatch(bot, messages, chatId)
                        }
                    }
                } catch (t: Throwable) {
                    AppConfig.logger.error("Error processing message buffer: ${t.message}", t)
                }
            }
        }

        bot.startPolling()
    }


    /**
     * Process a batch of messages for a specific chat
     */
    private suspend fun processMessagesBatch(bot: Bot, messages: List<Message>, chatId: Long) {
        require(messages.isNotEmpty()) { "Cannot process empty message batch" } // check invariant

        // Log how many messages we're processing
        AppConfig.logger.info("Processing batch of ${messages.size} messages for chat $chatId")

        // Process the batch
        val response = geminiClient.processBatchMessages(messages, telegramService)

        // Send the response
        if (response != null) {
            // Reply to the last message in the batch
            bot.sendMessage(
                ChatId.fromId(chatId),
                response,
                replyToMessageId = messages.singleOrNull()?.messageId
            )
        } else {
            AppConfig.logger.warn("Failed to get response from Gemini for chat $chatId batch")
        }
    }
}
