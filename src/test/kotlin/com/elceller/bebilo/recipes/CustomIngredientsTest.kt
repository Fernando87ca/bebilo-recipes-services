package com.elceller.bebilo.recipes

import com.elceller.bebilo.recipes.api.response.*
import com.elceller.bebilo.recipes.data.mapper.*
import com.elceller.bebilo.recipes.domain.model.*
import com.elceller.bebilo.recipes.domain.repository.*
import com.elceller.bebilo.recipes.domain.usecase.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.plugins.di.provide
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

@OptIn(ExperimentalCoroutinesApi::class)
class CustomIngredientsTest {
    private val declared = RecipeAllergen("declared", "Declarado")
    private val milk = RecipeAllergen("milk", "Leche")
    private fun request() = creationRequest().copy(ingredients = creationRequest().ingredients +
        RecipeIngredientDto("Yogur natural", listOf(RecipeAllergenDto(declared.code, declared.name)),
            RecipeIngredientCategoryDto.Custom, quantity = 20.0, unit = "g", preparation = "Natural"))

    @TestFactory fun `POST stores manual input and GET preserves persisted custom in all states`() =
        listOf("steps", "narrative").map { style -> DynamicTest.dynamicTest(style) { testApplication {
            val repo = MemoryRecipes(); val jobs = mutableListOf<GenerationJob>()
            application { module {
                provide<AuthUserRepository> { creationAuth() }; provide<RecipeRepository> { repo }
                provide<RecipeWriteRepository> { repo }; provide<CreationContextRepository> { creationContexts(style) }
                provide<GenerationDispatcher> { GenerationDispatcher { jobs.add(it) } }
            } }
            val response = client.post("/v1/recipe") {
                bearerAuth("token"); contentType(ContentType.Application.Json); setBody(Json.encodeToString(request()))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val initial = Json.decodeFromString<RecipeDto>(response.bodyAsText())
            assertEquals(RecipeStatusDto.Created, initial.status)
            assertEquals(request().ingredients, initial.ingredients)
            assertEquals(RecipeIngredientCategory.Custom, repo.contexts.values.single().request.ingredients.last().category)
            val job = jobs.single()
            for (status in RecipeStatus.entries) {
                repo.save("uid", job.documentId, complete(job.initial).copy(status = status))
                val detail = client.get("/v1/recipe/${initial.summary.id}") { bearerAuth("token") }
                val listed = client.get("/v1/recipes") { bearerAuth("token") }
                assertEquals(HttpStatusCode.OK, detail.status); assertEquals(HttpStatusCode.OK, listed.status)
                val value = Json.decodeFromString<RecipeDto>(detail.bodyAsText())
                assertEquals(value, Json.decodeFromString<RecipesResponseDto>(listed.bodyAsText()).recipes.single())
                assertEquals(RecipeIngredientCategoryDto.Custom, value.ingredients.last().category)
                assertEquals("Yogur natural", value.ingredients.last().name)
            }
        } } }

    @TestFactory fun `custom generation classifies and supplements allergens or preserves input on failure`() =
        listOf("steps", "narrative").flatMap { style ->
            listOf("valid", "custom-output", "removed-allergen", "changed-allergen", "ordinary-allergen", "ordinary-category", "changed-name", "changed-quantity", "changed-preparation", "unknown-ingredient", "custom-added").map { scenario ->
                DynamicTest.dynamicTest("$style $scenario") { runTest {
                    val repo = MemoryRecipes(); val jobs = mutableListOf<GenerationJob>()
                    CreateRecipeUseCase(creationAuth(), creationContexts(style), { repo }, { GenerationDispatcher { jobs.add(it) } })("token", request().toDomain())
                    val job = jobs.single()
                    val base = complete(job.initial).copy(ingredients = complete(job.initial).ingredients.mapIndexed { i, v ->
                        if (i == 1) v.copy(quantity = job.initial.ingredients[i].quantity) else v })
                    var ingredients = base.ingredients.map { if (it.category == RecipeIngredientCategory.Custom)
                        it.copy(category = RecipeIngredientCategory.Dairy, allergens = it.allergens + milk) else it }
                    ingredients = when (scenario) {
                        "custom-output" -> ingredients.mapIndexed { i, v -> if (i == 1) v.copy(category = RecipeIngredientCategory.Custom) else v }
                        "removed-allergen" -> ingredients.mapIndexed { i, v -> if (i == 1) v.copy(allergens = listOf(milk)) else v }
                        "changed-allergen" -> ingredients.mapIndexed { i, v -> if (i == 1) v.copy(allergens = listOf(declared.copy(name = "Cambiado"), milk)) else v }
                        "ordinary-allergen" -> ingredients.mapIndexed { i, v -> if (i == 0) v.copy(allergens = listOf(milk)) else v }
                        "ordinary-category" -> ingredients.mapIndexed { i, v -> if (i == 0) v.copy(category = RecipeIngredientCategory.Fruit) else v }
                        "changed-name" -> ingredients.mapIndexed { i, v -> if (i == 1) v.copy(name = "Otro") else v }
                        "changed-quantity" -> ingredients.mapIndexed { i, v -> if (i == 1) v.copy(quantity = 99.0) else v }
                        "changed-preparation" -> ingredients.mapIndexed { i, v -> if (i == 1) v.copy(preparation = "Otra") else v }
                        "custom-added" -> ingredients + RecipeIngredient("Extra", emptyList(), RecipeIngredientCategory.Custom)
                        "unknown-ingredient" -> emptyList()
                        else -> ingredients
                    }
                    val output = base.copy(ingredients = ingredients, summary = base.summary.copy(allergens = ingredientAllergens(ingredients))).verifiedFor(job.context)
                    val generator = object : RecipeGenerator {
                        override suspend fun compose(context: GenerationContext, initial: Recipe) = output
                        override suspend fun planImages(context: GenerationContext, recipe: Recipe) = emptyList<PlannedImage>()
                    }
                    val worker = RecipeGenerationService(repo, { generator }, { error("unused") }, { error("unused") },
                        scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
                    try {
                        worker.submit(job); advanceUntilIdle()
                        val result = repo.values.values.single()
                        if (scenario in listOf("valid", "ordinary-allergen")) {
                            assertEquals(RecipeStatus.Ready, result.status)
                            assertEquals(RecipeIngredientCategory.Dairy, result.ingredients.last().category)
                            assertEquals(ingredientAllergens(ingredients), result.summary.allergens)
                            assertTrue(result.ingredients.first().allergens.containsAll(job.initial.ingredients.first().allergens))
                        } else {
                            assertEquals(RecipeStatus.Error, result.status)
                            assertEquals(job.initial.ingredients, result.ingredients)
                            assertEquals(job.initial.summary.allergens, result.summary.allergens)
                        }
                        assertEquals(listOf(RecipeStatus.Created, RecipeStatus.Loading), repo.history.take(2).map { it.status })
                    } finally { worker.close() }
                } }
            }
        }
}
