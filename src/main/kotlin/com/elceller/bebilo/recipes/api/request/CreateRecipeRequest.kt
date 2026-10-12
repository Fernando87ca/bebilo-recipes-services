package com.elceller.bebilo.recipes.api.request

import com.elceller.bebilo.recipes.api.response.RecipeIngredientDto
import kotlinx.serialization.Serializable

@Serializable
data class CreateRecipeRequest(
    val babyIds: List<String>,
    val ingredients: List<RecipeIngredientDto>,
    val cookingMethods: List<String>,
    val title: String? = null,
    val servings: Int? = null,
    val additionalInstructions: String? = null,
)
