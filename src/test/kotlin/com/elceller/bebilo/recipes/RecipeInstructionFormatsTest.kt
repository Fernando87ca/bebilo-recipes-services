package com.elceller.bebilo.recipes

import com.elceller.bebilo.recipes.api.response.RecipeDto
import com.elceller.bebilo.recipes.data.mapper.*
import com.elceller.bebilo.recipes.domain.model.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class RecipeInstructionFormatsTest {
    @Test fun `step tasks preserve order images and format through storage and HTTP`() {
        val original = recipe().copy(instructions = RecipeInstructions.Steps(listOf(
            RecipeStep("s1", "Preparar", listOf("Pica la cebolla.", "Calienta el aceite.", "Cocina hasta ablandar."),
                5, RecipeImage("https://example.com/step.png")),
            RecipeStep("s2", "Servir", listOf("Deja templar.", "Sirve.")))))
        val stored = original.toStorageMap().toRecipe()
        assertEquals(original, stored)
        val json = Json { encodeDefaults = true }
        val response = json.encodeToJsonElement(stored.toResponse()).jsonObject
        val instructions = response.getValue("instructions").jsonObject
        assertEquals("steps", instructions.getValue("type").jsonPrimitive.content)
        val step = instructions.getValue("steps").jsonArray.first().jsonObject
        assertFalse(step.containsKey("text") || step.containsKey("title"))
        assertEquals(listOf("Pica la cebolla.", "Calienta el aceite.", "Cocina hasta ablandar."),
            step.getValue("tasks").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(stored.toResponse(), json.decodeFromJsonElement<RecipeDto>(response))
    }

    @Test fun `narrative retains prose and images`() {
        val original = storedRecipe(type = "narrative").toRecipe()
        assertEquals(original, original.toStorageMap().toRecipe())
        val instructions = Json.encodeToJsonElement(original.toResponse()).jsonObject.getValue("instructions").jsonObject
        assertFalse(instructions.containsKey("steps"))
        assertEquals("Primer bloque.\nSegundo bloque.", instructions.getValue("paragraphs").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
    }

    @TestFactory fun `invalid steps are rejected without legacy conversion`() = listOf(
        "missing objective" to mapOf("tasks" to listOf("Tarea")),
        "blank objective" to mapOf("objective" to " ", "tasks" to listOf("Tarea")),
        "missing tasks" to mapOf("objective" to "Preparar"),
        "empty tasks" to mapOf("objective" to "Preparar", "tasks" to emptyList<String>()),
        "blank task" to mapOf("objective" to "Preparar", "tasks" to listOf(" ")),
        "non text task" to mapOf("objective" to "Preparar", "tasks" to listOf(1)),
        "legacy step" to mapOf("text" to "Preparar", "title" to "Objetivo"),
        "mixed old fields" to mapOf("objective" to "Preparar", "tasks" to listOf("Tarea"), "text" to "Antiguo"),
    ).map { (name, fields) -> DynamicTest.dynamicTest(name) {
        val document = storedRecipe().apply {
            this["instructions"] = mapOf("type" to "steps", "steps" to listOf(fields + ("id" to "s1")))
        }
        assertThrows(InvalidStoredRecipeException::class.java) { document.toRecipe() }
    } }
}
