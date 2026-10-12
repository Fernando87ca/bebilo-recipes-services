package com.elceller.bebilo.recipes.data.generation

import ai.koog.http.client.KoogHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.serializer
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.*
import kotlin.reflect.KClass

/** Native Koog serialization plus the cache boundary required by the selected GPT-6 models.
 * No retries, streaming, request logging, or provider error bodies are exposed by this transport.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
class ResponsesTransport(
    private val client: HttpClient,
    private val baseUrl: String,
    private val apiKey: () -> String,
    private val metrics: (Long, Long) -> Unit = { _, _ -> },
) : KoogHttpClient {

    override val clientName = "BebiloOpenAI"
    private val json = Json { ignoreUnknownKeys = true; namingStrategy = JsonNamingStrategy.SnakeCase }
    override suspend fun <T : Any, R : Any> post(path: String, requestBody: T, requestBodyType: KClass<T>,
        responseType: KClass<R>, parameters: Map<String, String>, headers: Map<String, String>): R {
        require(path.substringAfterLast('/') == "responses" && requestBody is String)
        val payload = addCacheBoundary(Json.parseToJsonElement(requestBody).jsonObject)
        val response = client.post("${baseUrl.trimEnd('/')}/responses") {
            contentType(ContentType.Application.Json)
            bearerAuth(apiKey())
            setBody(payload.toString())
        }
        check(response.status.isSuccess()) { "LLM request failed: HTTP ${response.status.value}" }
        val body = response.bodyAsText()
        val parsed = Json.parseToJsonElement(body).jsonObject
        check(parsed["status"]?.jsonPrimitive?.content == "completed") { "LLM response did not complete" }
        val usage = parsed["usage"]?.jsonObject
        metrics(usage?.get("input_tokens")?.jsonPrimitive?.longOrNull ?: 0,
            usage?.get("input_tokens_details")?.jsonObject?.get("cached_tokens")?.jsonPrimitive?.longOrNull ?: 0)
        @Suppress("UNCHECKED_CAST")
        return json.decodeFromString(serializer(responseType.java), body) as R
    }
    override suspend fun <R : Any> get(path: String, responseType: KClass<R>, parameters: Map<String, String>, headers: Map<String, String>): R =
        error("GET unsupported by recipe agent")
    override fun <T : Any, R : Any, O : Any> sse(path: String, requestBody: T, requestBodyType: KClass<T>,
        dataFilter: (String?) -> Boolean, decodeStreamingResponse: (String) -> R, processStreamingChunk: (R) -> O?,
        parameters: Map<String, String>, headers: Map<String, String>): Flow<O> = error("Streaming disabled")
    override fun <T : Any> lines(path: String, requestBody: T, requestBodyType: KClass<T>, parameters: Map<String, String>,
        headers: Map<String, String>): Flow<String> = error("Streaming disabled")
    override fun close() = client.close()
}

fun addCacheBoundary(payload: JsonObject): JsonObject {
    val input = payload.getValue("input").jsonArray.toMutableList()
    val index = input.indexOfFirst { it.jsonObject["role"]?.jsonPrimitive?.content == "developer" }
    require(index >= 0)
    val message = input[index].jsonObject
    val blocks = message.getValue("content").jsonArray.toMutableList()
    blocks[blocks.lastIndex] = JsonObject(blocks.last().jsonObject + ("prompt_cache_breakpoint" to buildJsonObject { put("mode", "explicit") }))
    input[index] = JsonObject(message + ("content" to JsonArray(blocks)))
    return JsonObject(payload + mapOf("input" to JsonArray(input),
        "prompt_cache_options" to buildJsonObject { put("mode", "explicit"); put("ttl", "30m") }))
}
