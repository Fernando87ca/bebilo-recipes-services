package com.elceller.bebilo.recipes.data.mapper

import com.elceller.bebilo.recipes.api.response.*
import com.elceller.bebilo.recipes.domain.model.*

fun Recipe.toResponse() = RecipeDto(
    summary = summary.toResponse(), ingredients = ingredients.map { it.toResponse() },
    cookingMethods = cookingMethods, instructions = instructions.toResponse(),
    imageMode = RecipeImageModeDto.valueOf(imageMode.name), description = description,
    preparationMinutes = preparationMinutes, cookingMinutes = cookingMinutes, servings = servings,
    storageNotes = storageNotes, adaptationNotes = adaptationNotes, createdAt = createdAt, updatedAt = updatedAt,
    status = RecipeStatusDto.valueOf(status.name),
    ingredientChanges = ingredientChanges.map {
        RecipeIngredientChangeDto(IngredientChangeActionDto.valueOf(it.action.name), it.originalIngredientIndex,
            it.originalIngredient.toResponse(), it.replacementIngredient?.toResponse(), it.reason)
    },
)
private fun RecipeSummary.toResponse() = RecipeSummaryDto(
    id, title, babies.map { RecipeBabyDto(it.id, it.name) }, allergens.map { it.toResponse() },
    coverImage?.toResponse(), recommendedAgeMonths, totalMinutes, texture, tags,
)
private fun RecipeAllergen.toResponse() = RecipeAllergenDto(code, name)
private fun RecipeImage.toResponse() = RecipeImageDto(url, altText)
fun RecipeIngredient.toResponse() = RecipeIngredientDto(
    name, allergens.map { it.toResponse() }, category.toResponse(), catalogId, quantity, unit, preparation,
)
private fun RecipeIngredientCategory.toResponse(): RecipeIngredientCategoryDto = when (this) {
    RecipeIngredientCategory.Vegetables -> RecipeIngredientCategoryDto.Vegetables
    RecipeIngredientCategory.Fruit -> RecipeIngredientCategoryDto.Fruit
    RecipeIngredientCategory.Protein -> RecipeIngredientCategoryDto.Protein
    RecipeIngredientCategory.Dairy -> RecipeIngredientCategoryDto.Dairy
    RecipeIngredientCategory.Cereal -> RecipeIngredientCategoryDto.Cereal
    RecipeIngredientCategory.Seeds -> RecipeIngredientCategoryDto.Seeds
    RecipeIngredientCategory.OilAndFats -> RecipeIngredientCategoryDto.OilAndFats
    RecipeIngredientCategory.Sweet -> RecipeIngredientCategoryDto.Sweet
    RecipeIngredientCategory.Custom -> RecipeIngredientCategoryDto.Custom
}
private fun RecipeInstructions.toResponse(): RecipeInstructionsDto = when (this) {
    is RecipeInstructions.Steps -> RecipeInstructionsDto.Steps(steps.map {
        RecipeStepDto(it.id, it.text, it.title, it.durationMinutes, it.image?.toResponse())
    })
    is RecipeInstructions.Narrative -> RecipeInstructionsDto.Narrative(paragraphs.map {
        RecipeParagraphDto(it.id, it.text, it.image?.toResponse())
    })
}
