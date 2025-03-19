package utils

import com.github.kotlintelegrambot.entities.Message
import config.AppConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Buffers messages per chat for a specified time window to allow batch processing
 */
class MessageBuffer(
    private val throttleTimeMs: Long = 5000 // Default 5 seconds throttling
) {
    private val buffers = ConcurrentHashMap<Long, MutableList<Message>>()
    private val processingFlags = ConcurrentHashMap<Long, Boolean>()
    private val mutex = Mutex()
    
    /**
     * Buffers a message for the specified throttling time and returns true if
     * this message should trigger processing (i.e., it's the first message after
     * the throttling window has elapsed)
     */
    suspend fun bufferMessage(chatId: Long, message: Message): Boolean {
        mutex.withLock {
            // Add message to the buffer
            val chatBuffer = buffers.getOrPut(chatId) { mutableListOf() }
            chatBuffer.add(message)
            
            // If already processing this chat, just buffer the message
            if (processingFlags.getOrPut(chatId) { false }) {
                return false
            }
            
            // Mark this chat as being processed
            processingFlags[chatId] = true
        }
        
        // Wait for the throttling time
        AppConfig.logger.debug("Starting throttling for chat $chatId for ${throttleTimeMs}ms")
        delay(throttleTimeMs)
        AppConfig.logger.debug("Throttling complete for chat $chatId")
        
        return true
    }
    
    /**
     * Gets and clears all buffered messages for a specific chat
     */
    suspend fun getAndClearMessages(chatId: Long): List<Message> {
        return mutex.withLock {
            val messages = buffers[chatId]?.toList() ?: emptyList()
            buffers[chatId]?.clear()
            processingFlags[chatId] = false
            messages
        }
    }
}
