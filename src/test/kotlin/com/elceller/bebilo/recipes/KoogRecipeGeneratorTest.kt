package com.elceller.bebilo.recipes

import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import com.elceller.bebilo.recipes.data.generation.*
import com.elceller.bebilo.recipes.data.mapper.*
import com.elceller.bebilo.recipes.data.repository.KtorCreationContextRepository
import com.elceller.bebilo.recipes.domain.model.*
import com.elceller.bebilo.recipes.domain.repository.GenerationDispatcher
import com.elceller.bebilo.recipes.domain.usecase.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.DynamicTest
import java.io.ByteArrayOutputStream
import java.awt.image.BufferedImage
import java.util.Base64
import javax.imageio.ImageIO

/** Exercises real Koog agents and serialization, replacing only the network boundary. */
class KoogRecipeGeneratorTest {
    @TestFactory fun `Koog sends selected models styles schema and stable cache boundary`() =
        listOf("gpt-6-luna", "gpt-6.1-sol", "gpt-6-astra").flatMap { model ->
            listOf("steps", "narrative").map { style -> DynamicTest.dynamicTest("$model $style") { runBlocking {
                val repo = MemoryRecipes(); val jobs = mutableListOf<GenerationJob>()
                CreateRecipeUseCase(creationAuth(), creationContexts(style, "multiple"), { repo }, { GenerationDispatcher { jobs.add(it) } })("token", creationRequest().toDomain())
                val job = jobs.single(); val context = job.context.copy(settings = job.context.settings.copy(modelId = model))
                val result = generatedJson(complete(job.initial))
                val requests = mutableListOf<JsonObject>(); val metrics = mutableListOf<Pair<Long, Long>>()
                val engine = MockEngine { request ->
                    assertEquals("/v1/responses", request.url.encodedPath)
                    assertEquals("Bearer test-secret", request.headers[HttpHeaders.Authorization])
                    val payload = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    requests.add(payload)
                    val agentName = payload.getValue("text").jsonObject.getValue("format").jsonObject.getValue("name").jsonPrimitive.content
                    val output = if (agentName != "visual-planning") safetyMockOutput(payload, result) else """{"images":[{"instructionId":null,"prompt":"Plato terminado","altText":"Patata"},{"instructionId":"${if (style == "steps") "s1" else "p1"}","prompt":"Proceso terminado","altText":"Proceso"}]}"""
                    respond(responsesJson(output, model), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                val http = HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
                val agent = KoogRecipeGenerator(OpenAILLMClient(httpClient = ResponsesTransport(http, "https://example.invalid/v1",
                    { "test-secret" }) { input, cached -> metrics.add(input to cached) }), PromptResources())
                try {
                    val recipe = agent.compose(context, job.initial)
                    validateComposition(job.copy(context = context), recipe)
                    val plan = agent.planImages(context, recipe); validateImagePlan(recipe, plan)
                    agent.compose(context.copy(babies = context.babies.map { it.copy(name = "Otro nombre") }), job.initial)
                    val compositionRequest = requests[2]
                    val compositionFormat = compositionRequest.getValue("text").jsonObject.getValue("format").jsonObject
                    assertEquals("composition-$style", compositionFormat.getValue("name").jsonPrimitive.content)
                    assertTrue(compositionRequest.getValue("input").toString().contains(
                        if (style == "steps") "ESTILO PASO A PASO CONCISO" else "ESTILO NARRADO"))
                    assertFalse(compositionRequest.getValue("input").toString().contains(
                        if (style == "steps") "ESTILO NARRADO" else "ESTILO PASO A PASO CONCISO"))
                    val instructionSchema = compositionFormat.getValue("schema").jsonObject
                        .getValue("properties").jsonObject.getValue("instructions").jsonObject.getValue("properties").jsonObject
                    assertEquals(style, instructionSchema.getValue("type").jsonObject.getValue("enum").jsonArray.single().jsonPrimitive.content)
                    if (style == "steps") {
                        val fields = instructionSchema.getValue("steps").jsonObject.getValue("items").jsonObject.getValue("properties").jsonObject
                        assertTrue(fields.containsKey("objective") && fields.containsKey("tasks"))
                        assertFalse(fields.containsKey("text") || fields.containsKey("title"))
                    }
                    val visualFormat = requests[4].getValue("text").jsonObject.getValue("format").jsonObject
                    val imagesSchema = visualFormat.getValue("schema").jsonObject.getValue("properties").jsonObject.getValue("images").jsonObject
                    assertEquals(5, imagesSchema.getValue("maxItems").jsonPrimitive.int)
                    assertTrue(requests[4].getValue("input").toString().contains("hasta 4 imágenes de instrucciones"))
                    assertEquals(9, requests.size)
                    requests.forEach { payload ->
                        assertEquals(model, payload.getValue("model").jsonPrimitive.content)
                        assertEquals(false, payload.getValue("store").jsonPrimitive.boolean)
                        assertEquals("explicit", payload.getValue("prompt_cache_options").jsonObject.getValue("mode").jsonPrimitive.content)
                        val prefix = payload.getValue("input").jsonArray.first().jsonObject
                        assertEquals("developer", prefix.getValue("role").jsonPrimitive.content)
                        assertEquals("explicit", prefix.getValue("content").jsonArray.last().jsonObject.getValue("prompt_cache_breakpoint")
                            .jsonObject.getValue("mode").jsonPrimitive.content)
                        assertEquals(true, payload.getValue("text").jsonObject.getValue("format").jsonObject.getValue("strict").jsonPrimitive.boolean)
                    }
                    assertEquals(requests.first().getValue("input").jsonArray.first(), requests[5].getValue("input").jsonArray.first())
                    assertEquals(requests.first()["prompt_cache_key"], requests[5]["prompt_cache_key"])
                    assertNotEquals(requests.first()["prompt_cache_key"], requests[4]["prompt_cache_key"])
                    assertFalse(requests[4].getValue("input").toString().contains("Nil"))
                    assertEquals(List(9) { 1200L to 1024L }, metrics)
                } finally { agent.close() }
            } } }
        }
    @Test fun `provider failure is never retried and error bodies stay private`() = runBlocking {
        var calls = 0
        val http = HttpClient(MockEngine { calls++; respond("private provider details", HttpStatusCode.TooManyRequests) })
        val agent = KoogRecipeGenerator(OpenAILLMClient(httpClient = ResponsesTransport(http, "https://example.invalid/v1", { "test-secret" })), PromptResources())
        val repo = MemoryRecipes(); val jobs = mutableListOf<GenerationJob>()
        CreateRecipeUseCase(creationAuth(), creationContexts(), { repo }, { GenerationDispatcher { jobs.add(it) } })("token", creationRequest().toDomain())
        try {
            val failure = assertThrows(Exception::class.java) { runBlocking { agent.compose(jobs.single().context, jobs.single().initial) } }
            assertFalse(failure.toString().contains("private provider details")); assertEquals(1, calls)
        } finally { agent.close() }
    }
    @Test fun `image API supplies jpeg with configured model without legacy response format`() = runBlocking {
        val jpeg = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray()
        var calls = 0
        val http = HttpClient(MockEngine { request ->
            calls++
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("gpt-image-2.5-flare", body.getValue("model").jsonPrimitive.content)
            assertEquals("jpeg", body.getValue("output_format").jsonPrimitive.content)
            assertEquals("medium", body.getValue("quality").jsonPrimitive.content)
            assertEquals("1024x1024", body.getValue("size").jsonPrimitive.content)
            assertFalse(body.containsKey("response_format"))
            respond("""{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(jpeg)}"}]}""", HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        })
        OpenAIImages(http, "https://example.invalid/v1", { "test-secret" }, "gpt-image-2.5-flare").use { images ->
            assertArrayEquals(jpeg, images.generate("Plato")); assertEquals(1, calls)
        }
    }
    @TestFactory fun `context services forward token and map failures`() = listOf(
        401 to UnauthorizedException::class.java, 403 to ForbiddenException::class.java,
        503 to ServiceUnavailableException::class.java, 500 to DependencyResponseException::class.java,
    ).map { (code, exception) -> DynamicTest.dynamicTest("dependency $code") {
        val client = HttpClient(MockEngine { request ->
            assertEquals("Bearer caller-token", request.headers[HttpHeaders.Authorization])
            respond("{}", HttpStatusCode.fromValue(code), headersOf(HttpHeaders.ContentType, "application/json"))
        })
        KtorCreationContextRepository("https://example.invalid/ai", "https://example.invalid/babies", client).use { contexts ->
            assertThrows(exception) { runBlocking { contexts.settings("caller-token") } }
            assertThrows(exception) { runBlocking { contexts.babies("caller-token") } }
        }
    } }
    @Test fun `missing baby restrictions are a dependency error rather than no restrictions`() {
        val client = HttpClient(MockEngine { respond(
            """[{"id":"baby-1","name":"Nil","birthDate":"2026-02-14","preferredTexture":"suave","foodInterest":"","description":""}]""",
            HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) })
        KtorCreationContextRepository("https://example.invalid/ai", "https://example.invalid/babies", client).use { contexts ->
            assertThrows(DependencyResponseException::class.java) { runBlocking { contexts.babies("token") } }
        }
    }
    @Test fun `an incomplete provider response cannot become Ready and is not retried`() = runBlocking {
        var calls = 0
        val http = HttpClient(MockEngine {
            calls++; respond(responsesJson("{}", "gpt-6-luna").replace("completed", "incomplete"), HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val repo = MemoryRecipes(); val jobs = mutableListOf<GenerationJob>()
        CreateRecipeUseCase(creationAuth(), creationContexts(), { repo }, { GenerationDispatcher { jobs.add(it) } })("token", creationRequest().toDomain())
        val agent = KoogRecipeGenerator(OpenAILLMClient(httpClient = ResponsesTransport(http, "https://example.invalid/v1", { "test-secret" })), PromptResources())
        try {
            assertThrows(Exception::class.java) { runBlocking { agent.compose(jobs.single().context, jobs.single().initial) } }
            assertEquals(1, calls)
        } finally { agent.close() }
    }
}

private val fixtureJson = Json { encodeDefaults = true }

fun generatedJson(recipe: Recipe): JsonObject {
    val value = fixtureJson.encodeToJsonElement(recipe.toResponse()).jsonObject
    val summary = value.getValue("summary").jsonObject
    return JsonObject(value.filterKeys { it in listOf("ingredients", "cookingMethods", "instructions", "description",
        "preparationMinutes", "cookingMinutes", "servings", "storageNotes", "adaptationNotes") } +
        summary.filterKeys { it in listOf("title", "recommendedAgeMonths", "totalMinutes", "texture", "tags") })
}
fun responsesJson(text: String, model: String): String = buildJsonObject {
    put("id", "response-test"); put("created_at", 1791792000); put("object", "response"); put("model", model)
    put("parallel_tool_calls", false); put("status", "completed"); put("text", buildJsonObject { put("format", buildJsonObject { put("type", "text") }) })
    put("output", buildJsonArray { add(buildJsonObject {
        put("id", "message-test"); put("type", "message"); put("role", "assistant"); put("status", "completed")
        put("content", buildJsonArray { add(buildJsonObject { put("type", "output_text"); put("text", text); put("annotations", JsonArray(emptyList())) }) })
    }) })
    put("usage", buildJsonObject {
        put("input_tokens", 1200); put("output_tokens", 100); put("total_tokens", 1300)
        put("input_tokens_details", buildJsonObject { put("cached_tokens", 1024) })
        put("output_tokens_details", buildJsonObject { put("reasoning_tokens", 0) })
    })
}.toString()
