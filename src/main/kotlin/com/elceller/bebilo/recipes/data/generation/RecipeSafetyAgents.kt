package com.elceller.bebilo.recipes.data.generation

import ai.koog.agents.core.agent.*
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.annotation.InternalAgentsApi
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.utils.time.KoogClock
import com.elceller.bebilo.recipes.api.response.RecipeIngredientDto
import com.elceller.bebilo.recipes.api.response.RecipeAllergenDto
import com.elceller.bebilo.recipes.domain.model.*
import kotlinx.serialization.Serializable

/** Canonical groups; additional stable food codes remain supported by the HTTP contract. */
object AllergenGroups {
    val names = linkedMapOf(
        "gluten" to "Cereales con gluten", "crustaceans" to "Crustáceos", "egg" to "Huevo",
        "fish" to "Pescado", "peanuts" to "Cacahuetes", "soy" to "Soja", "milk" to "Leche",
        "nuts" to "Frutos de cáscara", "celery" to "Apio", "mustard" to "Mostaza",
        "sesame" to "Sésamo", "sulphites" to "Sulfitos", "lupin" to "Altramuces", "molluscs" to "Moluscos",
    )

    fun merge(declared: List<RecipeAllergenDto>, detected: List<RecipeAllergenDto>): List<RecipeAllergenDto> {
        require(detected.map { it.code }.distinct().size == detected.size)
        detected.forEach {
            require(it.code.matches(Regex("[a-z][a-z0-9_]*")) && it.name.isNotBlank())
            require(names[it.code] == null || names[it.code] == it.name)
        }
        // Never rewrite caller declarations, including a display name for a canonical code.
        return (declared + detected).distinctBy { it.code }
    }
}

@Serializable
data class CulinaryBaby(val ageMonths: Int, val feedingStage: String?, val preferredTexture: String,
    val restrictions: List<String>, val foodInterest: String, val description: String)

fun GenerationContext.culinaryBabies() = babies.map {
    CulinaryBaby(it.ageMonths, it.feedingStage, it.preferredTexture, it.restrictions, it.foodInterest, it.description)
}

@Serializable
data class IngredientRole(val index: Int, val role: String)
@Serializable
data class CandidateIngredients(val ingredients: List<RecipeIngredientDto>, val roles: List<IngredientRole>)
@Serializable
data class SafetyInput(
    val ingredients: List<RecipeIngredientDto>,
    val roles: List<IngredientRole>,
    val babies: List<CulinaryBaby>,
    val cookingMethods: List<String>,
    val requestedTitle: String?,
    val recipe: GeneratedRecipe?,
    val omittedIngredients: List<RecipeIngredientDto> = emptyList(),
)
@Serializable
data class IngredientConflict(val babyIndex: Int, val restrictionIndex: Int, val reason: String)
@Serializable
data class IngredientAssessment(
    val index: Int, val name: String, val allergens: List<RecipeAllergenDto>,
    val identifiable: Boolean, val compatible: Boolean, val canOmit: Boolean,
    val conflicts: List<IngredientConflict>,
)
@Serializable
data class AllergenAnalysis(
    val complete: Boolean, val recipeCompatible: Boolean, val requestedTitleCompatible: Boolean,
    val omissionsAcceptable: Boolean, val ingredients: List<IngredientAssessment>,
)
@Serializable
data class AlternativesInput(val context: SafetyInput, val analysis: AllergenAnalysis)
@Serializable
data class IngredientAlternative(
    val index: Int, val action: String, val replacementIngredient: RecipeIngredientDto?, val reason: String,
    val omissionPreservesRecipe: Boolean,
)
@Serializable
data class IngredientAlternatives(val changes: List<IngredientAlternative>)

fun validateAnalysis(input: SafetyInput, analysis: AllergenAnalysis) {
    require(analysis.complete && analysis.omissionsAcceptable)
    require(analysis.ingredients.map { it.index }.sorted() == input.ingredients.indices.toList())
    analysis.ingredients.forEach { record ->
        val ingredient = input.ingredients[record.index]
        require(record.name == ingredient.name && record.identifiable)
        AllergenGroups.merge(ingredient.allergens, record.allergens)
        require(record.compatible == record.conflicts.isEmpty())
        record.conflicts.forEach {
            require(it.babyIndex in input.babies.indices)
            require(it.restrictionIndex in input.babies[it.babyIndex].restrictions.indices && it.reason.isNotBlank())
        }
    }
}

/** Native agent tools create a fresh agent per call; close and unregister it even on cancellation. */
@OptIn(InternalAgentsApi::class)
class ClosingAgentService<I, O>(
    override val promptExecutor: PromptExecutor,
    override val agentConfig: AIAgentConfig,
    private val strategy: AIAgentFunctionalStrategy<I, O>,
) : AIAgentServiceBase<I, O, FunctionalAIAgent<I, O>>() {
    override val toolRegistry = ToolRegistry.EMPTY
    override fun createManagedAgent(id: String?, additionalToolRegistry: ToolRegistry,
        agentConfig: AIAgentConfig, clock: KoogClock) = FunctionalAIAgent(
        promptExecutor, agentConfig, strategy, toolRegistry + additionalToolRegistry, id, clock)

    override suspend fun createAgentAndRun(agentInput: I, id: String?, additionalToolRegistry: ToolRegistry,
        agentConfig: AIAgentConfig, clock: KoogClock): O {
        val agent = createAgent(id, additionalToolRegistry, agentConfig, clock)
        try { return agent.run(agentInput) }
        finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { agent.close(); removeAgent(agent) }
        }
    }
}
