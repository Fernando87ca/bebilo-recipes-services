package com.elceller.bebilo.recipes.domain.model

import java.net.URI
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

fun Recipe.validateStoredContent() {
    valid(summary.id.isNotBlank() && summary.title.isNotBlank())
    valid(summary.babies.isNotEmpty())
    unique(summary.babies.map { it.id })
    summary.babies.forEach { valid(it.id.isNotBlank() && it.name.isNotBlank()) }
    validateAllergens(summary.allergens)
    summary.coverImage?.validate()
    nonNegative(summary.recommendedAgeMonths, summary.totalMinutes, preparationMinutes, cookingMinutes)
    valid(servings == null || servings > 0)
    listOf(summary.texture, description, storageNotes, adaptationNotes).forEach(::optionalText)
    summary.tags.forEach { valid(it.isNotBlank()) }
    valid(ingredients.isNotEmpty() && cookingMethods.isNotEmpty())
    cookingMethods.forEach { valid(it.isNotBlank()) }
    val declaredAllergens = summary.allergens.associateBy { it.code }
    ingredients.forEach { ingredient ->
        valid(ingredient.name.isNotBlank())
        validateAllergens(ingredient.allergens)
        ingredient.allergens.forEach { allergen -> valid(declaredAllergens[allergen.code] == allergen) }
        valid(ingredient.quantity == null || (ingredient.quantity.isFinite() && ingredient.quantity > 0))
        listOf(ingredient.catalogId, ingredient.unit, ingredient.preparation).forEach(::optionalText)
    }
    unique(ingredientChanges.map { it.originalIngredientIndex.toString() })
    ingredientChanges.forEach { change ->
        valid(change.originalIngredientIndex >= 0 && change.reason.isNotBlank())
        val original = change.originalIngredient
        valid(original.name.isNotBlank())
        validateAllergens(original.allergens)
        valid(original.quantity == null || (original.quantity.isFinite() && original.quantity > 0))
        listOf(original.catalogId, original.unit, original.preparation).forEach(::optionalText)
        valid((change.action == IngredientChangeAction.Omission) == (change.replacementIngredient == null))
        change.replacementIngredient?.let { valid(it.catalogId == null && it in ingredients) }
    }
    when (val value = instructions) {
        is RecipeInstructions.Steps -> {
            valid(status != RecipeStatus.Ready || value.steps.isNotEmpty())
            unique(value.steps.map { it.id })
            value.steps.forEach {
                valid(it.id.isNotBlank() && it.objective.isNotBlank())
                valid(it.tasks.isNotEmpty() && it.tasks.all { task -> task.isNotBlank() })
                nonNegative(it.durationMinutes)
                it.image?.validate()
            }
        }
        is RecipeInstructions.Narrative -> {
            valid(status != RecipeStatus.Ready || value.paragraphs.isNotEmpty())
            unique(value.paragraphs.map { it.id })
            value.paragraphs.forEach {
                valid(it.id.isNotBlank() && it.text.isNotBlank())
                it.image?.validate()
            }
        }
    }
    listOf(createdAt, updatedAt).forEach { date ->
        if (date != null) {
            try { OffsetDateTime.parse(date) } catch (_: DateTimeParseException) { throw InvalidStoredRecipeException() }
        }
    }
}

private fun RecipeImage.validate() {
    val uri = try { URI(url) } catch (_: Exception) { throw InvalidStoredRecipeException() }
    valid(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null)
    optionalText(altText)
}
private fun validateAllergens(values: List<RecipeAllergen>) {
    unique(values.map { it.code })
    values.forEach { valid(it.code.isNotBlank() && it.name.isNotBlank()) }
}
private fun unique(values: List<String>) = valid(values.distinct().size == values.size)
private fun optionalText(value: String?) = valid(value == null || value.isNotBlank())
private fun nonNegative(vararg values: Int?) = values.forEach { valid(it == null || it >= 0) }
private fun valid(condition: Boolean) { if (!condition) throw InvalidStoredRecipeException() }
