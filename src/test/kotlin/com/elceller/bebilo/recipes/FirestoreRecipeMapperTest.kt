package com.elceller.bebilo.recipes

import com.elceller.bebilo.recipes.data.mapper.toRecipe
import com.elceller.bebilo.recipes.domain.model.InvalidStoredRecipeException
import com.google.cloud.Timestamp
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class FirestoreRecipeMapperTest {
    @TestFactory fun `corrupt storage cannot produce a valid recipe`() = listOf<Pair<String, (MutableMap<String, Any?>) -> Unit>>(
        "missing status" to { it.remove("status") },
        "null status" to { it["status"] = null },
        "unknown status" to { it["status"] = "ready" },
        "missing ingredients" to { it.remove("ingredients") },
        "null ingredients" to { it["ingredients"] = null },
        "null ingredient" to { it["ingredients"] = listOf(null) },
        "empty ingredients" to { it["ingredients"] = emptyList<Any>() },
        "empty methods" to { it["cookingMethods"] = emptyList<String>() },
        "unknown image mode" to { it["imageMode"] = "multiple" },
        "unknown instructions" to { it["instructions"] = mapOf("type" to "unknown") },
        "mixed instructions" to { it["instructions"] = mapOf("type" to "steps", "steps" to listOf(mapOf("id" to "s", "text" to "text")), "paragraphs" to emptyList<Any>()) },
        "duplicate steps" to { it["instructions"] = mapOf("type" to "steps", "steps" to List(2) { mapOf("id" to "s", "text" to "text") }) },
        "empty steps" to { it["instructions"] = mapOf("type" to "steps", "steps" to emptyList<Any>()) },
        "negative time" to { it["cookingMinutes"] = -1L },
        "fractional time" to { it["cookingMinutes"] = 1.5 },
        "overflow time" to { it["cookingMinutes"] = Long.MAX_VALUE },
        "zero servings" to { it["servings"] = 0L },
        "date without timezone" to { it["createdAt"] = "2026-10-12T10:00:00" },
        "invalid date" to { it["createdAt"] = "today" },
    ).map { (name, mutate) -> DynamicTest.dynamicTest(name) {
        val document = storedRecipe(); mutate(document)
        assertThrows(InvalidStoredRecipeException::class.java) { document.toRecipe() }
    } }

    @TestFactory fun `invalid ingredient categories cannot produce a recipe`() = invalidIngredientCategories.map { (name, change) ->
        DynamicTest.dynamicTest(name) {
            val document = storedRecipe().also { it.editIngredient(change) }
            assertThrows(InvalidStoredRecipeException::class.java) { document.toRecipe() }
        }
    }

    @Test fun `native timestamps preserve seconds and nanoseconds`() {
        val document = storedRecipe()
        document["createdAt"] = Timestamp.ofTimeSecondsAndNanos(1791792000L, 123456789)
        document["updatedAt"] = null
        assertEquals("2026-10-12T08:00:00.123456789Z", document.toRecipe().createdAt)
        assertNull(document.toRecipe().updatedAt)
    }
    @Test fun `optional omissions and nulls are supported without making up fields`() {
        val document = storedRecipe()
        listOf("description", "preparationMinutes", "cookingMinutes", "servings", "storageNotes", "adaptationNotes", "createdAt", "updatedAt").forEach { document.remove(it) }
        document["description"] = null
        val summary = (document.getValue("summary") as Map<*, *>).entries.associate { it.key as String to it.value }.toMutableMap()
        listOf("coverImage", "recommendedAgeMonths", "totalMinutes", "texture", "tags").forEach { summary.remove(it) }
        document["summary"] = summary
        document["ingredients"] = listOf(mapOf("name" to "Libre", "category" to "vegetables", "allergens" to emptyList<Any>()))
        val recipe = document.toRecipe()
        assertNull(recipe.description); assertNull(recipe.createdAt); assertNull(recipe.ingredients.single().quantity)
        assertTrue(recipe.summary.tags.isEmpty())
        summary["tags"] = null
        assertThrows(InvalidStoredRecipeException::class.java) { document.toRecipe() }
    }
    @Test fun `invalid quantities allergens and identity are rejected`() {
        for (quantity in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, "20")) {
            val document = storedRecipe()
            document["ingredients"] = listOf(mapOf("name" to "Ingrediente", "category" to "vegetables", "allergens" to emptyList<Any>(), "quantity" to quantity))
            assertThrows(InvalidStoredRecipeException::class.java) { document.toRecipe() }
        }
        val document = storedRecipe()
        document["ingredients"] = listOf(mapOf("name" to "Leche", "category" to "dairy", "allergens" to listOf(mapOf("code" to "unknown", "name" to "Otro"))))
        assertThrows(InvalidStoredRecipeException::class.java) { document.toRecipe() }
        val summary = (document.getValue("summary") as Map<*, *>).entries.associate { it.key as String to it.value }.toMutableMap()
        document["ingredients"] = storedRecipe()["ingredients"]
        document["summary"] = summary
        for (invalid in listOf(" ", "")) {
            summary["id"] = invalid
            assertThrows(InvalidStoredRecipeException::class.java) { document.toRecipe() }
        }
    }
}
