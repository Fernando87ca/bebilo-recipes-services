package com.elceller.bebilo.recipes.data.generation

import com.elceller.bebilo.recipes.domain.repository.RecipeImageGenerator
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.imageio.ImageIO

class OpenAISecret(private val file: String?, private val apiKey: String? = null) {
    private val secret: String by lazy {
        if (file.isNullOrBlank()) {
            return@lazy requireNotNull(apiKey?.takeIf(String::isNotBlank)) { "OPENAI_API_KEY or OPENAI_API_KEY_FILE is required for generation" }
        }
        // Exceptions deliberately omit the path and filesystem error details.
        val value = try { Files.readString(Path.of(file)).trim() } catch (_: Exception) { error("Cannot read LLM secret") }
        require(value.isNotBlank()) { "LLM secret is empty" }
        value
    }
    fun read(): String = secret
}

class OpenAIImages(
    private val client: HttpClient, private val baseUrl: String, private val apiKey: () -> String,
    private val model: String,
) : RecipeImageGenerator, AutoCloseable {
    override suspend fun generate(prompt: String): ByteArray {
        val response = client.post("${baseUrl.trimEnd('/')}/images/generations") {
            bearerAuth(apiKey()); contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("model", model); put("prompt", prompt); put("n", 1)
                put("quality", "medium"); put("size", "1024x1024"); put("output_format", "jpeg")
            }.toString())
        }
        check(response.status.isSuccess()) { "Image request failed: HTTP ${response.status.value}" }
        val encoded = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("data")
            .jsonArray.single().jsonObject.getValue("b64_json").jsonPrimitive.content
        require(encoded.length <= 28_000_000) { "Image too large" }
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte())
        require(ImageIO.read(bytes.inputStream()) != null) { "Invalid image" }
        return bytes
    }
    override fun close() = client.close()
}
