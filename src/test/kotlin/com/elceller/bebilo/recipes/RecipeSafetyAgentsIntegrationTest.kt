package com.elceller.bebilo.recipes

import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import com.elceller.bebilo.recipes.api.response.*
import com.elceller.bebilo.recipes.data.generation.*
import com.elceller.bebilo.recipes.data.mapper.*
import com.elceller.bebilo.recipes.data.repository.FirestoreRecipeRepository
import com.elceller.bebilo.recipes.domain.model.*
import com.elceller.bebilo.recipes.domain.repository.*
import com.elceller.bebilo.recipes.domain.usecase.*
import com.google.cloud.firestore.FirestoreOptions
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.server.plugins.di.provide
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.util.UUID

class RecipeSafetyAgentsIntegrationTest {
    @TestFactory fun `native agent changes survive Firestore and both authenticated GETs`() =
        listOf("steps", "narrative").flatMap { style -> listOf("replacement", "omission").map { scenario ->
            DynamicTest.dynamicTest("$style $scenario") { testApplication {
                val firestore = FirestoreOptions.newBuilder().setProjectId("demo-bebilo-recipes").build().service
                val uid = UUID.randomUUID().toString(); val repo = FirestoreRecipeRepository(firestore)
                val jobs = mutableListOf<GenerationJob>()
                val auth = object : AuthUserRepository { override suspend fun getUserUid(bearerToken: String) = uid }
                application { module {
                    provide<AuthUserRepository> { auth }; provide<RecipeRepository> { repo }
                } }
                val input = creationRequest().copy(ingredients = creationRequest().ingredients +
                    RecipeIngredientDto("Yogur natural", emptyList(), RecipeIngredientCategoryDto.Custom, catalogId = "manual-original"))
                var generator: KoogRecipeGenerator? = null
                try {
                    CreateRecipeUseCase(auth, creationContexts(style), { repo }, { GenerationDispatcher { jobs.add(it) } })("fixture-token", input.toDomain())
                    val job = jobs.single()
                    val http = HttpClient(MockEngine { request ->
                        val payload = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        respond(responsesJson(safetyMockOutput(payload, generatedJson(complete(job.initial)), scenario), "gpt-6-luna"),
                            HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    })
                    val agent = KoogRecipeGenerator(OpenAILLMClient(httpClient = ResponsesTransport(http, "https://example.invalid/v1",
                        { "dummy-key" })), PromptResources())
                    generator = agent
                    val composed = agent.compose(job.context, job.initial)
                    validateComposition(job, composed)
                    val ready = composed.copy(status = RecipeStatus.Ready)
                    repo.save(uid, job.documentId, ready)
                    assertEquals(ready.copy(safetyVerification = null), repo.get(uid, ready.summary.id))
                    val ref = firestore.collection("users").document(uid).collection("recipes").document(job.documentId)
                    val before = ref.get().get().data!!
                    assertTrue(before.containsKey("ingredientChanges")); assertFalse(before.containsKey("safetyVerification"))
                    val detail = client.get("/v1/recipe/${ready.summary.id}") { bearerAuth("fixture-token") }
                    val listing = client.get("/v1/recipes") { bearerAuth("fixture-token") }
                    assertEquals(HttpStatusCode.OK, detail.status); assertEquals(HttpStatusCode.OK, listing.status)
                    val dto = Json.decodeFromString<RecipeDto>(detail.bodyAsText())
                    assertEquals(ready.toResponse(), dto)
                    assertEquals(dto, Json.decodeFromString<RecipesResponseDto>(listing.bodyAsText()).recipes.single())
                    assertEquals(before, ref.get().get().data)
                    assertEquals(input.ingredients.last(), dto.ingredientChanges.single().originalIngredient)
                    assertTrue(dto.summary.allergens.isEmpty())
                } finally {
                    generator?.close()
                    jobs.forEach { firestore.collection("users").document(uid).collection("recipes").document(it.documentId).delete().get() }
                    firestore.close()
                }
            } }
        } }
}
