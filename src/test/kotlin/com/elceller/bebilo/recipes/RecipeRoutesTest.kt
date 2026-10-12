package com.elceller.bebilo.recipes

import com.elceller.bebilo.recipes.api.response.*
import com.elceller.bebilo.recipes.data.mapper.*
import com.elceller.bebilo.recipes.domain.model.*
import com.elceller.bebilo.recipes.domain.repository.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.plugins.di.provide
import io.ktor.server.testing.testApplication
import io.mockk.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class RecipeRoutesTest {
    @TestFactory fun `both GET preserve representations and states`() = listOf("Loading", "Ready", "Error").flatMap { status ->
        listOf("steps", "narrative").flatMap { type -> listOf("none", "cover", "contextual").map { mode ->
            DynamicTest.dynamicTest("$status $type $mode") { testApplication {
                val value = storedRecipe(type = type, mode = mode, status = status).toRecipe()
                val repository = mockk<RecipeRepository>()
                coEvery { repository.getAll("uid") } returns listOf(value)
                coEvery { repository.get("uid", value.summary.id) } returns value
                application { module { provide<RecipeRepository> { repository }; provide<AuthUserRepository> { auth() } } }
                val list = client.get("/v1/recipes") { bearerAuth("token") }
                val single = client.get("/v1/recipe/${value.summary.id}") { bearerAuth("token") }
                assertEquals(HttpStatusCode.OK, list.status)
                assertEquals(HttpStatusCode.OK, single.status)
                assertTrue(list.contentType()?.match(ContentType.Application.Json) == true)
                val expected = Json.encodeToJsonElement(value.toResponse())
                assertEquals(expected, Json.parseToJsonElement(single.bodyAsText()))
                assertEquals(expected, Json.parseToJsonElement(list.bodyAsText()).jsonObject.getValue("recipes").jsonArray.single())
            } }
        } }
    }
    @TestFactory fun `both GET preserve every category for each status and catalog identity`() =
        ingredientCategories.flatMap { category -> listOf("Loading", "Ready", "Error").map { status ->
            DynamicTest.dynamicTest("$category $status") { testApplication {
                val catalogs = listOf<String?>(null, "17", "future-id")
                val document = storedRecipe(status = status)
                document["ingredients"] = catalogs.map { catalog -> buildMap<String, Any?> {
                    // Deliberately unrelated name: category is never inferred from translated text.
                    put("name", "Leche"); put("allergens", emptyList<Any>()); put("category", category)
                    if (catalog != null) put("catalogId", catalog)
                } } + mapOf("name" to "Libre", "allergens" to emptyList<Any>(), "category" to category, "catalogId" to null)
                val value = document.toRecipe()
                val repository = mockk<RecipeRepository>()
                coEvery { repository.getAll("uid") } returns listOf(value)
                coEvery { repository.get("uid", "recipe-001") } returns value
                application { module { provide<RecipeRepository> { repository }; provide<AuthUserRepository> { auth() } } }
                for (path in listOf("/v1/recipes", "/v1/recipe/recipe-001")) {
                    val response = client.get(path) { bearerAuth("token") }
                    assertEquals(HttpStatusCode.OK, response.status)
                    val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                    val returned = if (path == "/v1/recipes") json.getValue("recipes").jsonArray.single().jsonObject else json
                    assertEquals(status, returned.getValue("status").jsonPrimitive.content)
                    val ingredients = returned.getValue("ingredients").jsonArray
                    assertEquals(4, ingredients.size)
                    ingredients.forEach { assertEquals(category, it.jsonObject.getValue("category").jsonPrimitive.content) }
                    assertEquals(listOf(null, "17", "future-id", null), ingredients.map {
                        it.jsonObject["catalogId"]?.jsonPrimitive?.contentOrNull
                    })
                }
            } }
        } }

    @Test fun `list includes more than twenty ignoring remote filters`() = testApplication {
        val repository = mockk<RecipeRepository>()
        coEvery { repository.getAll("uid") } returns (1..25).map { recipe("recipe-$it") }
        application { module { provide<RecipeRepository> { repository }; provide<AuthUserRepository> { auth() } } }
        val response = client.get("/v1/recipes?limit=1&babyId=other") { bearerAuth("token") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(25, Json.decodeFromString<RecipesResponseDto>(response.bodyAsText()).recipes.size)
    }
    @Test fun `empty collection gives empty envelope`() = testApplication {
        val repository = mockk<RecipeRepository>()
        coEvery { repository.getAll("uid") } returns emptyList()
        application { module { provide<RecipeRepository> { repository }; provide<AuthUserRepository> { auth() } } }
        assertEquals("{\"recipes\":[]}", client.get("/v1/recipes") { bearerAuth("token") }.bodyAsText())
    }
    @Test fun `IDs preserve reserved characters unicode whitespace and percent escapes`() = testApplication {
        val repository = mockk<RecipeRepository>()
        val ids = listOf("one/two ?#%", "literal%2F", " padded ", "puré-你好", "a+b")
        ids.forEach { id -> coEvery { repository.get("uid", id) } returns recipe(id) }
        application { module { provide<RecipeRepository> { repository }; provide<AuthUserRepository> { auth() } } }
        ids.forEach { id ->
            val response = client.get("/v1/recipe/${id.encodeURLPathPart()}") { bearerAuth("token") }
            assertEquals(HttpStatusCode.OK, response.status, id)
            assertEquals(id, Json.decodeFromString<RecipeDto>(response.bodyAsText()).summary.id)
            coVerify(exactly = 1) { repository.get("uid", id) }
        }
    }
    @Test fun `missing and invalid bearer do not resolve storage`() = testApplication {
        val identity = auth()
        coEvery { identity.getUserUid("invalid") } throws UnauthorizedException()
        application { module {
            provide<RecipeRepository> { error("Storage resolved before authentication") }
            provide<AuthUserRepository> { identity }
        } }
        for (path in listOf("/v1/recipes", "/v1/recipe/id")) {
            for (authorization in listOf(null, "Basic token", "Bearer", "Bearer invalid")) {
                val response = client.get(path) { if (authorization != null) header(HttpHeaders.Authorization, authorization) }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
                assertEquals("UNAUTHORIZED", Json.decodeFromString<ApiErrorResponse>(response.bodyAsText()).code)
            }
        }
    }
    @Test fun `blank ID fails without reading storage`() = testApplication {
        val repository = mockk<RecipeRepository>()
        application { module { provide<RecipeRepository> { repository }; provide<AuthUserRepository> { auth() } } }
        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/recipe/%20%20") { bearerAuth("token") }.status)
        coVerify(exactly = 0) { repository.get(any(), any()) }
    }
    @Test fun `missing recipe returns the defined error`() = testApplication {
        val repository = mockk<RecipeRepository>()
        coEvery { repository.get("uid", any()) } returns null
        application { module { provide<RecipeRepository> { repository }; provide<AuthUserRepository> { auth() } } }
        val response = client.get("/v1/recipe/absent") { bearerAuth("token") }
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(ApiErrorResponse("NOT_FOUND", "Receta no encontrada"), Json.decodeFromString<ApiErrorResponse>(response.bodyAsText()))
    }
    @TestFactory fun `storage failures are never empty success`() = listOf(
        ServiceUnavailableException() to HttpStatusCode.ServiceUnavailable,
        InvalidStoredRecipeException() to HttpStatusCode.InternalServerError,
    ).map { (exception, status) -> DynamicTest.dynamicTest(exception.code) { testApplication {
        val repository = mockk<RecipeRepository>()
        coEvery { repository.getAll("uid") } throws exception
        coEvery { repository.get("uid", "id") } throws exception
        application { module { provide<RecipeRepository> { repository }; provide<AuthUserRepository> { auth() } } }
        for (path in listOf("/v1/recipes", "/v1/recipe/id")) {
            val response = client.get(path) { bearerAuth("token") }
            assertEquals(status, response.status)
            assertEquals(exception.code, Json.decodeFromString<ApiErrorResponse>(response.bodyAsText()).code)
        }
    } } }
    @Test fun `forbidden identity never resolves storage`() = testApplication {
        val identity = auth()
        coEvery { identity.getUserUid("token") } throws ForbiddenException()
        application { module { provide<AuthUserRepository> { identity }; provide<RecipeRepository> { error("storage") } } }
        assertEquals(HttpStatusCode.Forbidden, client.get("/v1/recipes") { bearerAuth("token") }.status)
    }
    @Test fun `health OpenAPI and Swagger work without Firebase`() = testApplication {
        application { module() }
        assertEquals("OK", client.get("/health").bodyAsText())
        val spec = client.get("/openapi.yaml")
        assertEquals(HttpStatusCode.OK, spec.status)
        assertTrue(spec.bodyAsText().contains("/v1/recipe/{recipeId}"))
        assertEquals(HttpStatusCode.OK, client.get("/swagger").status)
    }
    private fun auth() = mockk<AuthUserRepository>().also { coEvery { it.getUserUid("token") } returns "uid" }
}
