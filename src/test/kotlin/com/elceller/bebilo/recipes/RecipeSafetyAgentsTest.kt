package com.elceller.bebilo.recipes

import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import com.elceller.bebilo.recipes.api.response.*
import com.elceller.bebilo.recipes.data.generation.*
import com.elceller.bebilo.recipes.data.mapper.*
import com.elceller.bebilo.recipes.domain.model.*
import com.elceller.bebilo.recipes.domain.repository.*
import com.elceller.bebilo.recipes.domain.usecase.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.DynamicTest

/** Mock only the provider HTTP boundary: native Koog parent, agent-tools, subagents and schemas run. */
fun safetyMockOutput(payload: JsonObject, composition: JsonObject, scenario: String = "valid"): String {
    val phase = payload.getValue("text").jsonObject.getValue("format").jsonObject.getValue("name").jsonPrimitive.content
    val input = Json.parseToJsonElement(payload.getValue("input").jsonArray.last().jsonObject
        .getValue("content").jsonArray.first().jsonObject.getValue("text").jsonPrimitive.content).jsonObject
    fun ingredient(value: JsonElement): RecipeIngredientDto = Json.decodeFromJsonElement(value)
    fun allergens(name: String) = when {
        name.contains("yogur", true) || name.contains("leche", true) -> listOf(RecipeAllergenDto("milk", "Leche"))
        name.contains("huevo", true) -> listOf(RecipeAllergenDto("egg", "Huevo"))
        name.contains("cacahuete", true) -> listOf(RecipeAllergenDto("peanuts", "Cacahuetes"))
        else -> emptyList()
    }
    val output: JsonElement = when (phase) {
        "ingredient-candidates" -> {
            val ingredients = input.getValue("request").jsonObject.getValue("ingredients").jsonArray.map(::ingredient).map {
                it.copy(category = if (it.category == RecipeIngredientCategoryDto.Custom) RecipeIngredientCategoryDto.Dairy else it.category,
                    quantity = it.quantity ?: 100.0, unit = it.unit ?: "g")
            } + if (scenario in listOf("added", "added-conflict", "multiple-babies")) listOf(RecipeIngredientDto("Huevo", emptyList(), RecipeIngredientCategoryDto.Protein, quantity = 10.0, unit = "g")) else emptyList()
            val completeIngredients = ingredients + if (scenario == "declared-alias") listOf(RecipeIngredientDto("Leche", emptyList(),
                RecipeIngredientCategoryDto.Dairy, quantity = 5.0, unit = "g")) else emptyList()
            Json.encodeToJsonElement(CandidateIngredients(completeIngredients, completeIngredients.mapIndexed { i, _ -> IngredientRole(i, "Base del plato") }))
        }
        "allergen-analysis" -> {
            val safety = Json.decodeFromJsonElement<SafetyInput>(JsonObject(input - "allergenGroups"))
            val isFinal = safety.recipe != null
            var records = safety.ingredients.mapIndexed { index, item ->
                val detected = allergens(item.name)
                val all = (detected + item.allergens).map { it.code }
                val conflicts = safety.babies.flatMapIndexed { babyIndex, baby -> baby.restrictions.mapIndexedNotNull { ri, restriction ->
                    val conflict = ("milk" in all && restriction.contains("leche")) ||
                        ("egg" in all && restriction.contains("huevo")) || ("peanuts" in all && restriction.contains("cacahuete"))
                    if (conflict) IngredientConflict(babyIndex, ri, "Ingrediente incompatible") else null
                } }
                IngredientAssessment(index, item.name, detected, scenario != "unknown", conflicts.isEmpty(),
                    scenario != "omission-essential", conflicts)
            }
            if (scenario == "missing-analysis") records = records.dropLast(1)
            if (scenario == "wrong-name") records = records.map { it.copy(name = "Otro ingrediente") }
            val finalConflict = scenario == "final-conflict" && isFinal
            if (finalConflict) records = records.map { it.copy(compatible = false,
                conflicts = listOf(IngredientConflict(0, 0, "Conflicto final"))) }
            Json.encodeToJsonElement(AllergenAnalysis(scenario != "ambiguous", scenario != "hidden-ingredient" || !isFinal,
                scenario != "title-change", scenario != "omission-disagreed" || safety.omittedIngredients.isEmpty(), records))
        }
        "ingredient-alternatives" -> {
            val alternative = Json.decodeFromJsonElement<AlternativesInput>(JsonObject(input - "allergenGroups"))
            Json.encodeToJsonElement(IngredientAlternatives(alternative.analysis.ingredients.filter { !it.compatible }.map { conflict ->
                val omit = scenario.startsWith("omission")
                IngredientAlternative(conflict.index, if (omit) "omission" else "replacement",
                    if (omit) null else RecipeIngredientDto(if (scenario == "unsafe-alternative") "Leche" else if (scenario == "unsafe-other-baby") "Huevo" else "Calabacín",
                        emptyList(), RecipeIngredientCategoryDto.Vegetables, quantity = 80.0, unit = "g", preparation = "Pelado"),
                    "Se adapta a las restricciones alimentarias", omit)
            }))
        }
        "composition-steps", "composition-narrative" -> JsonObject(composition + mapOf(
            "ingredients" to if (scenario == "unapproved-composition") composition.getValue("ingredients") else input.getValue("approvedIngredients"),
            "adaptationNotes" to if (input.getValue("ingredientChanges").jsonArray.isNotEmpty()) JsonPrimitive("Se sustituye u omite el ingrediente incompatible.") else JsonNull,
            "title" to JsonPrimitive(if (scenario == "title-change") "Verduras al vapor" else input.getValue("request").jsonObject["title"]
                ?.jsonPrimitive?.contentOrNull ?: "Patata al vapor")))
        else -> error("Unexpected phase: $phase")
    }
    return output.toString()
}

@OptIn(ExperimentalCoroutinesApi::class)
class RecipeSafetyAgentsTest {
    @TestFactory fun `mandatory native tools verify replace omit and reject unsafe recipes`() =
        listOf("steps", "narrative").flatMap { style -> listOf("no-restrictions", "replacement", "added", "multiple-babies",
            "omission", "omission-essential", "omission-disagreed", "unsafe-alternative", "missing-analysis", "wrong-name",
            "unknown", "ambiguous", "final-conflict", "hidden-ingredient", "unapproved-composition", "title-change", "analysis-provider-failure", "alternatives-provider-failure", "added-conflict", "unsafe-other-baby", "declared-alias").map { scenario ->
            DynamicTest.dynamicTest("$style $scenario") { runBlocking {
                val repo = MemoryRecipes(); val jobs = mutableListOf<GenerationJob>()
                val hasMilk = scenario !in listOf("added", "added-conflict", "final-conflict", "hidden-ingredient")
                val ingredients = listOf(RecipeIngredientDto("Patata", emptyList(), RecipeIngredientCategoryDto.Vegetables)) +
                    if (hasMilk) listOf(RecipeIngredientDto("Yogur natural", if (scenario == "declared-alias") listOf(RecipeAllergenDto("milk", "Nombre declarado"))
                        else emptyList(), RecipeIngredientCategoryDto.Dairy, catalogId = "catalog-yogurt")) else emptyList()
                val babies = listOf(GenerationBaby("baby-1", "Nombre privado", java.time.LocalDate.now(java.time.ZoneId.of("Europe/Madrid")).minusMonths(8).toString(), 8, "Puré", "suave",
                    if (scenario in listOf("no-restrictions", "declared-alias")) emptyList() else listOf("sin leche"))) +
                    if (scenario in listOf("multiple-babies", "added-conflict", "unsafe-other-baby")) listOf(GenerationBaby("baby-2", "Otro nombre privado", java.time.LocalDate.now(java.time.ZoneId.of("Europe/Madrid")).minusMonths(9).toString(), 9, restrictions = listOf("sin huevo"))) else emptyList()
                val contexts = object : CreationContextRepository {
                    override suspend fun settings(token: String) = AiSettings(style, "none", "gpt-6-luna")
                    override suspend fun babies(token: String) = babies.map { CreationBaby(it.id, it.name, it.birthDate, it.feedingStage, it.preferredTexture, it.restrictions, it.foodInterest, it.description) }
                }
                val request = creationRequest().copy(babyIds = babies.map { it.id }, ingredients = ingredients,
                    title = if (scenario == "title-change") "Patata con yogur" else null)
                CreateRecipeUseCase(creationAuth(), contexts, { repo }, { GenerationDispatcher { jobs.add(it) } })("token", request.toDomain())
                val job = jobs.single(); val requests = mutableListOf<JsonObject>(); val reports = mutableListOf<String>()
                val http = HttpClient(MockEngine { request ->
                    val payload = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    requests.add(payload)
                    assertFalse(payload.toString().contains("Nombre privado"))
                    assertFalse(payload.toString().contains("Otro nombre privado"))
                    val phase = payload.getValue("text").jsonObject.getValue("format").jsonObject.getValue("name").jsonPrimitive.content
                    if ((scenario == "analysis-provider-failure" && phase == "allergen-analysis") ||
                        (scenario == "alternatives-provider-failure" && phase == "ingredient-alternatives")) {
                        respond("private provider error", HttpStatusCode.TooManyRequests)
                    } else respond(responsesJson(safetyMockOutput(payload, generatedJson(complete(job.initial)), scenario), "gpt-6-luna"),
                        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                })
                val generator = KoogRecipeGenerator(OpenAILLMClient(httpClient = ResponsesTransport(http, "https://example.invalid/v1",
                    { "dummy-key" })), PromptResources(), reports::add)
                var imageCalls = 0
                val done = CompletableDeferred<Unit>()
                val storage = object : RecipeWriteRepository by repo {
                    override suspend fun save(ownerUid: String, documentId: String, recipe: Recipe, images: Map<String, String>) {
                        repo.save(ownerUid, documentId, recipe, images)
                        if (recipe.status in listOf(RecipeStatus.Ready, RecipeStatus.Error)) done.complete(Unit)
                    }
                }
                val worker = RecipeGenerationService(storage, { generator }, { imageCalls++; error("unused") }, { error("unused") })
                try {
                    worker.submit(job); withTimeout(10000) { done.await() }
                    val result = repo.values.values.single()
                    val success = scenario in listOf("no-restrictions", "replacement", "added", "multiple-babies", "omission", "title-change", "added-conflict", "declared-alias")
                    assertEquals(if (success) RecipeStatus.Ready else RecipeStatus.Error, result.status, reports.joinToString())
                    assertEquals(0, imageCalls)
                    assertFalse(reports.joinToString().contains("private provider error"))
                    val names = requests.map { it.getValue("text").jsonObject.getValue("format").jsonObject.getValue("name").jsonPrimitive.content }
                    assertEquals("ingredient-candidates", names.first())
                    assertTrue("allergen-analysis" in names)
                    assertTrue(names.count { it == "ingredient-alternatives" } <= 1)
                    if (scenario == "analysis-provider-failure") assertEquals(1, names.count { it == "allergen-analysis" })
                    if (scenario == "alternatives-provider-failure") assertEquals(1, names.count { it == "ingredient-alternatives" })
                    if (success) {
                        assertTrue(names.count { it == "allergen-analysis" } >= 2)
                        assertEquals("allergen-analysis", names.last())
                        assertEquals(ingredientAllergens(result.ingredients), result.summary.allergens)
                        if (scenario == "no-restrictions") assertEquals(listOf(RecipeAllergen("milk", "Leche")), result.summary.allergens)
                        if (scenario == "declared-alias") assertEquals(listOf(RecipeAllergen("milk", "Nombre declarado")), result.summary.allergens)
                        if (scenario == "added") assertEquals(listOf(RecipeAllergen("egg", "Huevo")), result.summary.allergens)
                        if (scenario == "added-conflict") {
                            assertTrue(result.summary.allergens.isEmpty()); assertTrue(result.ingredientChanges.isEmpty())
                            assertFalse(result.ingredients.any { it.name == "Huevo" }); assertFalse(result.adaptationNotes.isNullOrBlank())
                        }
                        if (scenario in listOf("replacement", "multiple-babies", "title-change", "omission")) {
                            val change = result.ingredientChanges.single()
                            assertEquals(1, change.originalIngredientIndex); assertEquals(job.initial.ingredients[1], change.originalIngredient)
                            assertFalse(result.ingredients.any { it.name == "Yogur natural" })
                            assertTrue(result.summary.allergens.isEmpty())
                            if (scenario == "omission") assertNull(change.replacementIngredient)
                            else { assertNull(change.replacementIngredient!!.catalogId); assertTrue(change.replacementIngredient in result.ingredients) }
                            assertEquals(result.ingredientChanges, result.toStorageMap().toRecipe().ingredientChanges)
                        }
                    } else {
                        assertEquals(job.initial.ingredients, result.ingredients)
                        assertEquals(job.initial.summary.allergens, result.summary.allergens)
                    }
                } finally { worker.close(); generator.close() }
            } }
        } }

    @Test fun `worker rejects absent or stale safety proof`() = runTest {
        val repo = MemoryRecipes(); val jobs = mutableListOf<GenerationJob>()
        CreateRecipeUseCase(creationAuth(), creationContexts(), { repo }, { GenerationDispatcher { jobs.add(it) } })("token", creationRequest().toDomain())
        val job = jobs.single(); val value = complete(job.initial)
        for (invalid in listOf(value.copy(safetyVerification = null), value.copy(ingredients = value.ingredients +
            RecipeIngredient("Huevo", listOf(RecipeAllergen("egg", "Huevo")), RecipeIngredientCategory.Protein)),
            value.copy(instructions = RecipeInstructions.Steps(listOf(RecipeStep("s", "Añadir leche", listOf("Añadir leche"))))))) {
            assertThrows(Exception::class.java) { validateComposition(job, invalid) }
        }
        assertThrows(Exception::class.java) { validateComposition(job.copy(context = job.context.copy(babies = job.context.babies.map {
            it.copy(restrictions = listOf("sin huevo")) })), value) }
    }

    @Test fun `allergen groups retain declarations and accept additional stable food codes`() {
        val declared = RecipeAllergenDto("milk", "Nombre declarado")
        val detected = listOf(RecipeAllergenDto("milk", "Leche"), RecipeAllergenDto("strawberry", "Fresa"))
        assertEquals(listOf(declared, detected.last()), AllergenGroups.merge(listOf(declared), detected))
        assertThrows(IllegalArgumentException::class.java) { AllergenGroups.merge(emptyList(), detected + detected.first()) }
        assertThrows(IllegalArgumentException::class.java) { AllergenGroups.merge(emptyList(), listOf(RecipeAllergenDto("milk", "Otro nombre"))) }
    }

    @Test fun `malformed change records never become a partial or repaired recipe`() {
        val original = storedRecipe().toRecipe().ingredients.single()
        val base = storedRecipe().toRecipe().copy(ingredientChanges = listOf(RecipeIngredientChange(
            IngredientChangeAction.Omission, 0, original, null, "Se retira ingrediente prescindible"))).toStorageMap()
        @Suppress("UNCHECKED_CAST")
        val change = (base.getValue("ingredientChanges") as List<Map<String, Any?>>).single()
        for (invalid in listOf(change - "replacementIngredient", change + ("originalIngredientIndex" to -1),
            change + ("action" to "unknown"), change + ("reason" to " "),
            change + ("replacementIngredient" to base.getValue("ingredients").let { (it as List<*>).first() }))) {
            assertThrows(InvalidStoredRecipeException::class.java) { (base + ("ingredientChanges" to listOf(invalid))).toRecipe() }
        }
        assertThrows(InvalidStoredRecipeException::class.java) { (base + ("ingredientChanges" to listOf(change, change))).toRecipe() }
    }

    @Test fun `legacy documents are read without persisting safety proof or repairing allergens`() {
        val legacy = storedRecipe()
        val value = legacy.toRecipe()
        assertTrue(value.ingredientChanges.isEmpty()); assertNull(value.safetyVerification)
        assertFalse(legacy.containsKey("ingredientChanges"))
        assertFalse(value.toStorageMap().containsKey("safetyVerification"))
    }
}
