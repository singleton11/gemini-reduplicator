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
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
    data class GeminiPart(val text: String)
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

suspend fun huefy(phrase: String): String? {
    val prompt = promptTemplate
        ?.replace("{{ placeholder }}", phrase) ?: error("Can not build prompt")
    val response = httpClient.post(GeminiRequestResource()) {
            contentType(ContentType.Application.Json)
            setBody(
                GeminiRequest(
                    listOf(
                        GeminiContent(
                            listOf(
                                GeminiContent.GeminiPart(prompt)
                            )
                        )
                    )
                )
            )
        }

        val resp = response.body<GeminiResponse>()
        return resp.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text
}

fun main(args: Array<String>) {
    runBlocking {
        println(huefy("Ну ты и приколист!"))
    }
}
