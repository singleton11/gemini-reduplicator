package config

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import io.ktor.client.plugins.resources.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

object AppConfig {
    val logger: Logger = LoggerFactory.getLogger("GeminiReducplicator")
    
    val geminiApiKey: String = System.getenv("GEMINI_API_KEY") ?: error("GEMINI_API_KEY environment variable is not set")
    val telegramToken: String = System.getenv("TELEGRAM_TOKEN") ?: error("TELEGRAM_TOKEN environment variable is not set")
    
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
    }
}
