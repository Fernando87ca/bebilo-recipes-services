package com.elceller.bebilo.recipes

import com.elceller.bebilo.recipes.data.mapper.toRecipe
import com.elceller.bebilo.recipes.domain.model.Recipe

fun storedRecipe(id: String = "recipe-001", type: String = "steps", mode: String = "none", status: String = "Ready"): MutableMap<String, Any?> = mutableMapOf(
    "status" to status,
    "summary" to mutableMapOf<String, Any?>(
        "id" to id, "title" to "Preparación guardada", "babies" to listOf(mapOf("id" to "baby-1", "name" to "Nil")),
        "allergens" to listOf(mapOf("code" to "milk", "name" to "Leche")),
        "coverImage" to mapOf("url" to "https://example.com/cover.png", "altText" to "Portada"),
        "recommendedAgeMonths" to 8L, "totalMinutes" to 15L, "texture" to "Puré", "tags" to listOf("quick", "future_tag"),
    ),
    "ingredients" to listOf(mapOf("name" to "Ingrediente", "category" to "dairy", "allergens" to listOf(mapOf("code" to "milk", "name" to "Leche")),
        "catalogId" to "catalog-1", "quantity" to 100.5, "unit" to "g", "preparation" to "Troceado")),
    "cookingMethods" to listOf("steam", "future_method"),
    "instructions" to if (type == "steps") mapOf("type" to type, "steps" to listOf(mapOf(
        "id" to "step-1", "text" to "Texto original", "title" to "Preparación", "durationMinutes" to 5L,
        "image" to mapOf("url" to "https://example.com/step.png", "altText" to "Paso"),
    ))) else mapOf("type" to type, "paragraphs" to listOf(mapOf(
        "id" to "paragraph-1", "text" to "Primer bloque.\nSegundo bloque.", "image" to mapOf("url" to "https://example.com/paragraph.png"),
    ))),
    "imageMode" to mode, "description" to "Descripción guardada", "preparationMinutes" to 5L, "cookingMinutes" to 10L,
    "servings" to 2L, "storageNotes" to "Nota guardada", "adaptationNotes" to "Adaptación guardada",
    "createdAt" to "2026-10-12T08:00:00Z", "updatedAt" to "2026-10-12T08:30:00Z",
)
fun recipe(id: String = "recipe-001"): Recipe = storedRecipe(id).toRecipe()

val ingredientCategories = listOf("vegetables", "fruit", "protein", "dairy", "cereal", "seeds", "oil_and_fats", "sweet", "custom")

/** Keep the complete ingredient while changing only the field under test. */
fun MutableMap<String, Any?>.editIngredient(change: (MutableMap<String, Any?>) -> Unit) {
    val ingredient = (getValue("ingredients") as List<*>).single() as Map<*, *>
    val fields = ingredient.entries.associate { it.key as String to it.value }.toMutableMap()
    change(fields)
    this["ingredients"] = listOf(fields)
}

val invalidIngredientCategories: List<Pair<String, (MutableMap<String, Any?>) -> Unit>> = listOf(
    "missing" to { it.remove("category") },
    "null" to { it["category"] = null },
    "unknown" to { it["category"] = "fish" },
    "wrong case" to { it["category"] = "Vegetables" },
    "blank" to { it["category"] = " " },
    "number" to { it["category"] = 1L },
)
