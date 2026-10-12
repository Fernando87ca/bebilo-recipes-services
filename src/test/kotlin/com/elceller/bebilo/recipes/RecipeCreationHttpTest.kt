package com.elceller.bebilo.recipes

import com.elceller.bebilo.recipes.api.request.CreateRecipeRequest
import com.elceller.bebilo.recipes.api.response.*
import com.elceller.bebilo.recipes.domain.model.*
import com.elceller.bebilo.recipes.domain.repository.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.plugins.di.provide
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.DynamicTest

class RecipeCreationHttpTest {
    @TestFactory fun `POST reports errors and never dispatches before successful commit`() = listOf(
        ValidationException() to 400, UnauthorizedException() to 401, ForbiddenException() to 403,
        BabyNotFoundException() to 404, DependencyResponseException() to 502, ServiceUnavailableException() to 503,
        InternalServiceException() to 500,
    ).map { (failure, code) -> DynamicTest.dynamicTest("POST $code") { testApplication {
        var dispatched = false
        application { module {
            provide<AuthUserRepository> { creationAuth() }
            provide<CreationContextRepository> { object : CreationContextRepository {
                override suspend fun settings(token: String): AiSettings = throw failure
                override suspend fun babies(token: String): List<GenerationBaby> = error("No call")
            } }
            provide<RecipeWriteRepository> { error("No storage") }
            provide<GenerationDispatcher> { GenerationDispatcher { dispatched = true } }
        } }
        val response = client.post("/v1/recipe") { bearerAuth("token"); contentType(ContentType.Application.Json); setBody(Json.encodeToString(creationRequest())) }
        assertEquals(code, response.status.value)
        assertEquals(failure.code, Json.decodeFromString<ApiErrorResponse>(response.bodyAsText()).code)
        assertFalse(dispatched)
    } } }
    @Test fun `malformed or missing body fields and bearer do not create`() = testApplication {
        application { module {
            provide<AuthUserRepository> { creationAuth() }
            provide<CreationContextRepository> { creationContexts() }
            provide<RecipeWriteRepository> { error("No storage") }
        } }
        for (body in listOf("{", "{}", """{"babyIds":["baby-1"],"ingredients":[{"name":"Patata","allergens":[]}],"cookingMethods":["steam"]}""")) {
            assertEquals(HttpStatusCode.BadRequest, client.post("/v1/recipe") { bearerAuth("token"); contentType(ContentType.Application.Json); setBody(body) }.status)
        }
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/recipe") { contentType(ContentType.Application.Json); setBody(Json.encodeToString(creationRequest())) }.status)
    }
    @Test fun `HTTP returns Created while composition is blocked and images require ownership`() = testApplication {
        val repo = MemoryRecipes(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val done = CompletableDeferred<Unit>()
        val jobs = mutableListOf<GenerationJob>()
        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val generator = object : RecipeGenerator {
            override suspend fun compose(context: GenerationContext, initial: Recipe): Recipe {
                entered.complete(Unit); release.await(); return complete(initial)
            }
            override suspend fun planImages(context: GenerationContext, recipe: Recipe) = emptyList<PlannedImage>()
        }
        val writes = object : RecipeWriteRepository by repo {
            override suspend fun save(ownerUid: String, documentId: String, recipe: Recipe, images: Map<String, String>) {
                repo.save(ownerUid, documentId, recipe, images); if (recipe.status == RecipeStatus.Ready) done.complete(Unit)
            }
        }
        val worker = com.elceller.bebilo.recipes.domain.usecase.RecipeGenerationService(writes, { generator }, { error("unused") }, { error("unused") },
            "https://example.com", scope = workerScope)
        val storage = object : RecipeImageStorage {
            override suspend fun upload(ownerUid: String, documentId: String, imageId: String, bytes: ByteArray) = error("unused")
            override suspend fun read(path: String) = byteArrayOf(1, 2, 3)
        }
        application { module {
            provide<AuthUserRepository> { creationAuth() }; provide<CreationContextRepository> { creationContexts() }
            provide<RecipeRepository> { repo }; provide<RecipeWriteRepository> { writes }; provide<RecipeImageStorage> { storage }
            provide<GenerationDispatcher> { GenerationDispatcher { jobs.add(it); worker.submit(it) } }
        } }
        try {
            val response = withTimeout(5000) { client.post("/v1/recipe") { bearerAuth("token"); contentType(ContentType.Application.Json); setBody(Json.encodeToString(creationRequest())) } }
            assertEquals(HttpStatusCode.OK, response.status)
            val value = Json.decodeFromString<RecipeDto>(response.bodyAsText())
            assertEquals(RecipeStatusDto.Created, value.status)
            withTimeout(5000) { entered.await() }; assertFalse(done.isCompleted)
            release.complete(Unit); withTimeout(5000) { done.await() }
            repo.images["uid" to jobs.single().documentId] = mapOf("image" to "private-path")
            val path = "/v1/recipe/${value.summary.id}/images/image"
            val image = client.get(path) { bearerAuth("token") }
            assertEquals(HttpStatusCode.OK, image.status); assertEquals(ContentType.Image.JPEG, image.contentType())
            assertArrayEquals(byteArrayOf(1, 2, 3), image.bodyAsBytes())
            assertEquals("private, no-store", image.headers[HttpHeaders.CacheControl])
            assertEquals(HttpStatusCode.Unauthorized, client.get(path).status)
            assertEquals(HttpStatusCode.NotFound, client.get(path) { bearerAuth("other") }.status)
            assertEquals(HttpStatusCode.NotFound, client.get("/v1/recipe/absent/images/image") { bearerAuth("token") }.status)
        } finally { release.complete(Unit); worker.close() }
    }
}
