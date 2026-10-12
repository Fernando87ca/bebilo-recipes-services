package com.elceller.bebilo.recipes.data.mapper

import com.elceller.bebilo.recipes.api.response.*
import com.elceller.bebilo.recipes.domain.model.*
import kotlinx.serialization.json.*

fun RecipeIngredientDto.toDomain() = RecipeIngredient(name,
    allergens.map { RecipeAllergen(it.code, it.name) }, RecipeIngredientCategory.valueOf(category.name),
    catalogId, quantity, unit, preparation)

fun RecipeInstructionsDto.toDomain(): RecipeInstructions = when (this) {
    is RecipeInstructionsDto.Steps -> RecipeInstructions.Steps(steps.map {
        RecipeStep(it.id, it.text, it.title, it.durationMinutes) // Only storage-confirmed images are attached by the server.
    })
    is RecipeInstructionsDto.Narrative -> RecipeInstructions.Narrative(paragraphs.map { RecipeParagraph(it.id, it.text) })
}

/** Firestore only receives its native scalar/map/list types, never DTOs or JSON wrappers. */
fun JsonElement.toStorageValue(): Any? = when (this) {
    JsonNull -> null
    is JsonObject -> mapValues { it.value.toStorageValue() }
    is JsonArray -> map { it.toStorageValue() }
    is JsonPrimitive -> if (isString) content else booleanOrNull ?: longOrNull ?: doubleOrNull
}

@Suppress("UNCHECKED_CAST")
fun Recipe.toStorageMap(): Map<String, Any?> = storageJson.encodeToJsonElement(toResponse()).toStorageValue() as Map<String, Any?>

// update() must also clear optional fields: omitting defaults would retain old root values in Firestore.
private val storageJson = Json { encodeDefaults = true; explicitNulls = true }

fun com.elceller.bebilo.recipes.api.request.CreateRecipeRequest.toDomain() = CreateRecipeInput(
    babyIds, ingredients.map { it.toDomain() }, cookingMethods, title, servings, additionalInstructions)

fun GenerationContext.toStorageMap(): Map<String, Any?> = mapOf(
    "settings" to storageJson.encodeToJsonElement(settings).toStorageValue(),
    "babies" to storageJson.encodeToJsonElement(babies).toStorageValue(),
    "request" to mapOf("babyIds" to request.babyIds, "ingredients" to request.ingredients.map { ingredient ->
        mapOf("name" to ingredient.name, "category" to when (ingredient.category) {
            RecipeIngredientCategory.OilAndFats -> "oil_and_fats"
            else -> ingredient.category.name.lowercase()
        }, "allergens" to ingredient.allergens.map { mapOf("code" to it.code, "name" to it.name) },
        "catalogId" to ingredient.catalogId, "quantity" to ingredient.quantity, "unit" to ingredient.unit,
        "preparation" to ingredient.preparation)
    }, "cookingMethods" to request.cookingMethods, "title" to request.title, "servings" to request.servings,
        "additionalInstructions" to request.additionalInstructions),
)
fun Any?.toJsonValue(): JsonElement = when (this) {
    null -> JsonNull
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(entries.associate { it.key as String to it.value.toJsonValue() })
    is List<*> -> JsonArray(map { it.toJsonValue() })
    else -> error("Unsupported context value")
}
