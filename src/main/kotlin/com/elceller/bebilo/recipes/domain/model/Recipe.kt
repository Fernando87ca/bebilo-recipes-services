package com.elceller.bebilo.recipes.domain.model

data class RecipeImage(val url: String, val altText: String? = null)

data class RecipeBaby(val id: String, val name: String)

data class RecipeAllergen(val code: String, val name: String)

data class RecipeSummary(
    val id: String,
    val title: String,
    val babies: List<RecipeBaby>,
    val allergens: List<RecipeAllergen>,
    val coverImage: RecipeImage? = null,
    val recommendedAgeMonths: Int? = null,
    val totalMinutes: Int? = null,
    val texture: String? = null,
    val tags: List<String> = emptyList(),
)

enum class RecipeIngredientCategory {
    Vegetables,
    Fruit,
    Protein,
    Dairy,
    Cereal,
    Seeds,
    OilAndFats,
    Sweet,
}

data class RecipeIngredient(
    val name: String,
    val allergens: List<RecipeAllergen>,
    val category: RecipeIngredientCategory,
    val catalogId: String? = null,
    val quantity: Double? = null,
    val unit: String? = null,
    val preparation: String? = null,
)

data class RecipeStep(
    val id: String,
    val text: String,
    val title: String? = null,
    val durationMinutes: Int? = null,
    val image: RecipeImage? = null,
)

data class RecipeParagraph(val id: String, val text: String, val image: RecipeImage? = null)


sealed interface RecipeInstructions {
    data class Steps(val steps: List<RecipeStep>) : RecipeInstructions

    data class Narrative(val paragraphs: List<RecipeParagraph>) : RecipeInstructions
}

enum class RecipeImageMode {
    None,
    Cover,
    Contextual,
}

enum class RecipeStatus {
    Created,
    Loading,
    Ready,
    Error,
}

data class Recipe(
    val summary: RecipeSummary,
    val ingredients: List<RecipeIngredient>,
    val cookingMethods: List<String>,
    val instructions: RecipeInstructions,
    val imageMode: RecipeImageMode,
    val description: String? = null,
    val preparationMinutes: Int? = null,
    val cookingMinutes: Int? = null,
    val servings: Int? = null,
    val storageNotes: String? = null,
    val adaptationNotes: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val status: RecipeStatus,
)
