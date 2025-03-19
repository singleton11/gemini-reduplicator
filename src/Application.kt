import gemini.GeminiClient
import telegram.BotService
import telegram.TelegramService

fun main() {
    val geminiClient = GeminiClient()
    val telegramService = TelegramService()
    val botService = BotService(geminiClient, telegramService)

    botService.startBot()
}
