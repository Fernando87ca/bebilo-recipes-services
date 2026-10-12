package com.elceller.bebilo.recipes

import com.elceller.bebilo.recipes.api.response.*
import com.elceller.bebilo.recipes.data.repository.FirestoreRecipeRepository
import com.elceller.bebilo.recipes.data.mapper.toResponse
import com.elceller.bebilo.recipes.domain.model.*
import com.elceller.bebilo.recipes.domain.repository.*
import com.google.cloud.Timestamp
import com.google.cloud.firestore.FirestoreOptions
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.plugins.di.provide
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

/** Executed only by integrationTest, against a demo project and local Firestore emulator. */
class FirestoreRecipeIntegrationTest {
    private fun firestore() = FirestoreOptions.newBuilder().setProjectId("demo-bebilo-recipes").build().service
    @Test fun `real SDK preserves stored recipes and isolates each UID through HTTP`() = testApplication {
        val store = firestore()
        val prefix = UUID.randomUUID().toString()
        val uidA = "$prefix-a"; val uidB = "$prefix-b"; val uidEmpty = "$prefix-empty"
        val documents = (1..25).map { number ->
            storedRecipe(if (number == 1) "one/two ?#%" else "recipe-$number",
                if (number % 2 == 0) "narrative" else "steps",
                listOf("none", "cover", "contextual")[number % 3], listOf("Loading", "Ready", "Error")[number % 3]).also { document ->
                    document.editIngredient { ingredient ->
                        ingredient["category"] = ingredientCategories[(number - 1) % ingredientCategories.size]
                        when (number % 4) {
                            0 -> ingredient.remove("catalogId")
                            1 -> ingredient["catalogId"] = null
                            2 -> ingredient["catalogId"] = "17"
                            3 -> ingredient["catalogId"] = "future-id"
                        }
                    }
                }
        }
        val references = mutableListOf<com.google.cloud.firestore.DocumentReference>()
        try {
            documents.forEachIndexed { index, data ->
                data["createdAt"] = Timestamp.ofTimeSecondsAndNanos(1791792000, 123456000)
                val reference = store.collection("users").document(uidA).collection("recipes").document("physical-$index")
                reference.set(data).get(); references.add(reference)
            }
            val private = store.collection("users").document(uidB).collection("recipes").document("private")
            private.set(storedRecipe("b-private")).get(); references.add(private)
            val repository = FirestoreRecipeRepository(store)
            val auth = object : AuthUserRepository {
                override suspend fun getUserUid(bearerToken: String) = when (bearerToken) {
                    "a" -> uidA; "b" -> uidB; "empty" -> uidEmpty; else -> throw UnauthorizedException()
                }
            }
            application { module { provide<RecipeRepository> { repository }; provide<AuthUserRepository> { auth } } }
            val listed = client.get("/v1/recipes") { bearerAuth("a") }
            assertEquals(HttpStatusCode.OK, listed.status)
            val values = Json.decodeFromString<RecipesResponseDto>(listed.bodyAsText()).recipes
            assertEquals(25, values.size)
            assertEquals(ingredientCategories.toSet(), Json.parseToJsonElement(listed.bodyAsText())
                .jsonObject.getValue("recipes").jsonArray.map {
                    it.jsonObject.getValue("ingredients").jsonArray.single().jsonObject.getValue("category").jsonPrimitive.content
                }.toSet())
            for (value in values) {
                val response = client.get("/v1/recipe/${value.summary.id.encodeURLPathPart()}") { bearerAuth("a") }
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals(value, Json.decodeFromString<RecipeDto>(response.bodyAsText()))
                val expected = documents.single { (it.getValue("summary") as Map<*, *>)["id"] == value.summary.id }
                val expectedIngredient = (expected.getValue("ingredients") as List<*>).single() as Map<*, *>
                val returnedIngredient = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                    .getValue("ingredients").jsonArray.single().jsonObject
                assertEquals(expectedIngredient["category"], returnedIngredient.getValue("category").jsonPrimitive.content)
                assertEquals(expectedIngredient["catalogId"], value.ingredients.single().catalogId)
                assertEquals("2026-10-12T08:00:00.123456Z", value.createdAt)
            }
            val privateResponse = client.get("/v1/recipe/b-private") { bearerAuth("a") }
            val missing = client.get("/v1/recipe/nonexistent") { bearerAuth("a") }
            assertEquals(HttpStatusCode.NotFound, privateResponse.status)
            assertEquals(missing.bodyAsText(), privateResponse.bodyAsText())
            assertEquals(1, Json.decodeFromString<RecipesResponseDto>(client.get("/v1/recipes") { bearerAuth("b") }.bodyAsText()).recipes.size)
            assertEquals("{\"recipes\":[]}", client.get("/v1/recipes") { bearerAuth("empty") }.bodyAsText())
            val again = runBlocking { repository.getAll(uidA) }
            assertEquals(values.toSet(), again.map { it.toResponse() }.toSet())
            assertEquals(25, store.collection("users").document(uidA).collection("recipes").get().get().size())
        } finally {
            references.forEach { it.delete().get() }; store.close()
        }
    }
    @Test fun `invalid categories fail both HTTP reads without partial results or repair`() = testApplication {
        val store = firestore()
        val collection = store.collection("users").document(UUID.randomUUID().toString()).collection("recipes")
        val valid = collection.document("valid")
        val corrupt = collection.document("corrupt")
        try {
            valid.set(storedRecipe("valid")).get()
            val repository = FirestoreRecipeRepository(store)
            val auth = object : AuthUserRepository {
                override suspend fun getUserUid(bearerToken: String) = collection.parent!!.id
            }
            application { module { provide<RecipeRepository> { repository }; provide<AuthUserRepository> { auth } } }
            for ((name, change) in invalidIngredientCategories) {
                corrupt.set(storedRecipe("corrupt").also { it.editIngredient(change) }).get()
                val before = corrupt.get().get()
                for (path in listOf("/v1/recipes", "/v1/recipe/corrupt")) {
                    val response = client.get(path) { bearerAuth("fixture") }
                    assertEquals(HttpStatusCode.InternalServerError, response.status, "$name $path")
                    assertEquals("INTERNAL_ERROR", Json.decodeFromString<ApiErrorResponse>(response.bodyAsText()).code)
                }
                val after = corrupt.get().get()
                assertEquals(before.data, after.data, name)
                assertEquals(before.updateTime, after.updateTime, name)
                assertEquals(2, collection.get().get().size())
                assertEquals(HttpStatusCode.OK, client.get("/v1/recipe/valid") { bearerAuth("fixture") }.status)
            }
        } finally { valid.delete().get(); corrupt.delete().get(); store.close() }
    }

    @Test fun `duplicates and corrupt documents are never silently omitted`() {
        val store = firestore()
        val uid = UUID.randomUUID().toString()
        val collection = store.collection("users").document(uid).collection("recipes")
        val first = collection.document("first"); val second = collection.document("second")
        try {
            first.set(storedRecipe("same")).get(); second.set(storedRecipe("same")).get()
            val repository = FirestoreRecipeRepository(store)
            assertThrows(InvalidStoredRecipeException::class.java) { runBlocking { repository.getAll(uid) } }
            assertThrows(InvalidStoredRecipeException::class.java) { runBlocking { repository.get(uid, "same") } }
            second.set(storedRecipe("corrupt").also { it.remove("status") }).get()
            assertThrows(InvalidStoredRecipeException::class.java) { runBlocking { repository.getAll(uid) } }
            assertThrows(InvalidStoredRecipeException::class.java) { runBlocking { repository.get(uid, "corrupt") } }
            assertEquals(2, collection.get().get().size())
        } finally { first.delete().get(); second.delete().get(); store.close() }
    }
}
