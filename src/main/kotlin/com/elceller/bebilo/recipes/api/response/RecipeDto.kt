package com.elceller.bebilo.recipes.api.response

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable data class RecipesResponseDto(val recipes: List<RecipeDto>)

@Serializable data class RecipeImageDto(val url: String, val altText: String? = null)

@Serializable data class RecipeBabyDto(val id: String, val name: String)

@Serializable data class RecipeAllergenDto(val code: String, val name: String)

@Serializable
data class RecipeSummaryDto(
    val id: String,
    val title: String,
    val babies: List<RecipeBabyDto>,
    val allergens: List<RecipeAllergenDto>,
    val coverImage: RecipeImageDto? = null,
    val recommendedAgeMonths: Int? = null,
    val totalMinutes: Int? = null,
    val texture: String? = null,
    val tags: List<String> = emptyList(),
)

@Serializable
enum class RecipeIngredientCategoryDto {
    @SerialName("vegetables") Vegetables,
    @SerialName("fruit") Fruit,
    @SerialName("protein") Protein,
    @SerialName("dairy") Dairy,
    @SerialName("cereal") Cereal,
    @SerialName("seeds") Seeds,
    @SerialName("oil_and_fats") OilAndFats,
    @SerialName("sweet") Sweet,
}

@Serializable
data class RecipeIngredientDto(
    val name: String,
    val allergens: List<RecipeAllergenDto>,
    val category: RecipeIngredientCategoryDto,
    val catalogId: String? = null,
    val quantity: Double? = null,
    val unit: String? = null,
    val preparation: String? = null,
)

@Serializable
data class RecipeStepDto(
    val id: String,
    val text: String,
    val title: String? = null,
    val durationMinutes: Int? = null,
    val image: RecipeImageDto? = null,
)

@Serializable
data class RecipeParagraphDto(val id: String, val text: String, val image: RecipeImageDto? = null)

/** The JSON discriminator is `type`, independently of the user's current AI settings. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface RecipeInstructionsDto {
    @Serializable
    @SerialName("steps")
    data class Steps(val steps: List<RecipeStepDto>) : RecipeInstructionsDto

    @Serializable
    @SerialName("narrative")
    data class Narrative(val paragraphs: List<RecipeParagraphDto>) : RecipeInstructionsDto
}

@Serializable
enum class RecipeImageModeDto {
    @SerialName("none") None,
    @SerialName("cover") Cover,
    @SerialName("contextual") Contextual,
}

@Serializable
enum class RecipeStatusDto {
    @SerialName("Created") Created,
    @SerialName("Loading") Loading,
    @SerialName("Ready") Ready,
    @SerialName("Error") Error,
}

@Serializable
data class RecipeDto(
    val summary: RecipeSummaryDto,
    val ingredients: List<RecipeIngredientDto>,
    val cookingMethods: List<String>,
    val instructions: RecipeInstructionsDto,
    val imageMode: RecipeImageModeDto,
    val description: String? = null,
    val preparationMinutes: Int? = null,
    val cookingMinutes: Int? = null,
    val servings: Int? = null,
    val storageNotes: String? = null,
    val adaptationNotes: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val status: RecipeStatusDto,
)
