package com.elceller.bebilo.recipes.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class AiSettings(val instructionStyle: String, val photoMode: String, val modelId: String) {
    fun validate() {
        if (instructionStyle !in listOf("steps", "narrative") || photoMode !in listOf("none", "single", "multiple") ||
            modelId !in listOf("gpt-6-luna", "gpt-6.1-sol", "gpt-6-astra")) throw DependencyResponseException()
    }
}

@Serializable
data class GenerationBaby(
    val id: String, val name: String, val ageMonths: Int,
    val feedingStage: String? = null, val preferredTexture: String = "",
    val restrictions: List<String> = emptyList(), val foodInterest: String = "", val description: String = "",
)

data class CreateRecipeInput(
    val babyIds: List<String>, val ingredients: List<RecipeIngredient>, val cookingMethods: List<String>,
    val title: String? = null, val servings: Int? = null, val additionalInstructions: String? = null,
)

data class GenerationContext(val request: CreateRecipeInput, val settings: AiSettings, val babies: List<GenerationBaby>)

data class GenerationJob(val ownerUid: String, val documentId: String, val initial: Recipe, val context: GenerationContext)
data class PlannedImage(val instructionId: String?, val prompt: String, val altText: String)
