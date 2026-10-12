package com.elceller.bebilo.recipes.data.mapper

import com.elceller.bebilo.recipes.domain.model.*
import com.google.cloud.Timestamp
import java.time.Instant

/** Firestore maps remain storage types until converted; missing required fields never get defaults. */
fun Map<String, Any?>.toRecipe(): Recipe = Recipe(
    summary = objectValue("summary").summary(),
    ingredients = array("ingredients").map { it.objectMap().ingredient() },
    cookingMethods = array("cookingMethods").map { it.stringValue() },
    instructions = objectValue("instructions").instructions(),
    imageMode = when (string("imageMode")) {
        "none" -> RecipeImageMode.None
        "cover" -> RecipeImageMode.Cover
        "contextual" -> RecipeImageMode.Contextual
        else -> invalid()
    },
    description = optionalString("description"), preparationMinutes = optionalInt("preparationMinutes"),
    cookingMinutes = optionalInt("cookingMinutes"), servings = optionalInt("servings"),
    storageNotes = optionalString("storageNotes"), adaptationNotes = optionalString("adaptationNotes"),
    createdAt = date("createdAt"), updatedAt = date("updatedAt"),
    status = when (string("status")) {
        "Loading" -> RecipeStatus.Loading
        "Ready" -> RecipeStatus.Ready
        "Error" -> RecipeStatus.Error
        else -> invalid()
    },
).also(Recipe::validateStoredContent)

private fun Map<String, Any?>.summary() = RecipeSummary(
    id = string("id"), title = string("title"),
    babies = array("babies").map { it.objectMap().let { baby -> RecipeBaby(baby.string("id"), baby.string("name")) } },
    allergens = allergens(), coverImage = optionalImage("coverImage"),
    recommendedAgeMonths = optionalInt("recommendedAgeMonths"), totalMinutes = optionalInt("totalMinutes"),
    texture = optionalString("texture"),
    tags = if (containsKey("tags")) array("tags").map { it.stringValue() } else emptyList(),
)
private fun Map<String, Any?>.ingredient() = RecipeIngredient(
    name = string("name"), allergens = allergens(),
    category = when (string("category")) {
        "vegetables" -> RecipeIngredientCategory.Vegetables
        "fruit" -> RecipeIngredientCategory.Fruit
        "protein" -> RecipeIngredientCategory.Protein
        "dairy" -> RecipeIngredientCategory.Dairy
        "cereal" -> RecipeIngredientCategory.Cereal
        "seeds" -> RecipeIngredientCategory.Seeds
        "oil_and_fats" -> RecipeIngredientCategory.OilAndFats
        "sweet" -> RecipeIngredientCategory.Sweet
        else -> invalid()
    },
    catalogId = optionalString("catalogId"),
    quantity = get("quantity")?.let { value ->
        when (value) {
            is Long -> value.toDouble()
            is Int -> value.toDouble()
            is Double -> value
            else -> invalid()
        }
    },
    unit = optionalString("unit"), preparation = optionalString("preparation"),
)
private fun Map<String, Any?>.allergens() = array("allergens").map {
    it.objectMap().let { allergen -> RecipeAllergen(allergen.string("code"), allergen.string("name")) }
}
private fun Map<String, Any?>.instructions(): RecipeInstructions = when (string("type")) {
    "steps" -> {
        if (containsKey("paragraphs")) invalid()
        RecipeInstructions.Steps(array("steps").map { value ->
            value.objectMap().let { step ->
                RecipeStep(step.string("id"), step.string("text"), step.optionalString("title"),
                    step.optionalInt("durationMinutes"), step.optionalImage("image"))
            }
        })
    }
    "narrative" -> {
        if (containsKey("steps")) invalid()
        RecipeInstructions.Narrative(array("paragraphs").map { value ->
            value.objectMap().let { paragraph ->
                RecipeParagraph(paragraph.string("id"), paragraph.string("text"), paragraph.optionalImage("image"))
            }
        })
    }
    else -> invalid()
}
private fun Map<String, Any?>.optionalImage(key: String): RecipeImage? = get(key)?.objectMap()?.let {
    RecipeImage(it.string("url"), it.optionalString("altText"))
}
private fun Map<String, Any?>.string(key: String): String = get(key).stringValue()
private fun Map<String, Any?>.optionalString(key: String): String? = get(key)?.stringValue()
private fun Map<String, Any?>.optionalInt(key: String): Int? = get(key)?.let {
    val number = when (it) { is Long -> it; is Int -> it.toLong(); else -> invalid() }
    if (number !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) invalid()
    number.toInt()
}
private fun Map<String, Any?>.date(key: String): String? = when (val value = get(key)) {
    null -> null
    is String -> value
    is Timestamp -> Instant.ofEpochSecond(value.seconds, value.nanos.toLong()).toString()
    else -> invalid()
}
private fun Map<String, Any?>.array(key: String): List<*> = get(key) as? List<*> ?: invalid()
private fun Map<String, Any?>.objectValue(key: String): Map<String, Any?> = get(key).objectMap()
private fun Any?.stringValue(): String = this as? String ?: invalid()
@Suppress("UNCHECKED_CAST")
private fun Any?.objectMap(): Map<String, Any?> {
    val map = this as? Map<*, *> ?: invalid()
    if (map.keys.any { it !is String }) invalid()
    return map as Map<String, Any?>
}
private fun invalid(): Nothing = throw InvalidStoredRecipeException()
