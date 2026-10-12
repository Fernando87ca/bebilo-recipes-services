package com.elceller.bebilo.recipes.data.generation

import ai.koog.agents.core.agent.FunctionalAIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.functionalStrategy
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.params.LLMParams
import com.elceller.bebilo.recipes.api.response.RecipeIngredientDto
import com.elceller.bebilo.recipes.api.response.RecipeInstructionsDto
import com.elceller.bebilo.recipes.data.mapper.toDomain
import com.elceller.bebilo.recipes.data.mapper.toJsonValue
import com.elceller.bebilo.recipes.data.mapper.toResponse
import com.elceller.bebilo.recipes.data.mapper.toStorageMap
import com.elceller.bebilo.recipes.domain.model.GenerationContext
import com.elceller.bebilo.recipes.domain.model.PlannedImage
import com.elceller.bebilo.recipes.domain.model.Recipe
import com.elceller.bebilo.recipes.domain.repository.RecipeGenerator
import com.elceller.bebilo.recipes.domain.usecase.ingredientAllergens
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class GeneratedRecipe(
    val title: String,
    val ingredients: List<RecipeIngredientDto>,
    val cookingMethods: List<String>,
    val instructions: RecipeInstructionsDto,
    val description: String?,
    val preparationMinutes: Int?,
    val cookingMinutes: Int?,
    val servings: Int?,
    val storageNotes: String?,
    val adaptationNotes: String?,
    val recommendedAgeMonths: Int?,
    val totalMinutes: Int?,
    val texture: String?,
    val tags: List<String>,
)

@Serializable
data class VisualPlan(val images: List<VisualTarget>)

@Serializable
data class VisualTarget(val instructionId: String?, val prompt: String, val altText: String)

class KoogRecipeGenerator(
    private val client: OpenAILLMClient,
    private val resources: PromptResources,
) : RecipeGenerator, AutoCloseable {

    private val executor = MultiLLMPromptExecutor(client)
    private val json = Json { ignoreUnknownKeys = false }

    override suspend fun compose(context: GenerationContext, initial: Recipe): Recipe {
        val schema = when (context.settings.instructionStyle) {
            "steps" -> resources.stepsSchema
            else -> resources.narrativeSchema
        }
        val result = json.decodeFromString<GeneratedRecipe>(
            runAgent(
                name = "composition",
                instructions = resources.composition,
                schema = schema,
                modelId = context.settings.modelId,
                input = context.toStorageMap().toJsonValue().toString(),
            )
        )
        val ingredients = result.ingredients.map { it.toDomain() }

        return initial.copy(
            summary = initial.summary.copy(
                title = result.title, allergens = ingredientAllergens(ingredients),
                recommendedAgeMonths = result.recommendedAgeMonths, totalMinutes = result.totalMinutes,
                texture = result.texture, tags = result.tags, coverImage = null
            ),
            ingredients = ingredients,
            cookingMethods = result.cookingMethods,
            instructions = result.instructions.toDomain(),
            description = result.description,
            preparationMinutes = result.preparationMinutes,
            cookingMinutes = result.cookingMinutes,
            servings = result.servings,
            storageNotes = result.storageNotes,
            adaptationNotes = result.adaptationNotes,
        )
    }

    override suspend fun planImages(context: GenerationContext, recipe: Recipe): List<PlannedImage> {
        val input = buildJsonObject {
            put("photoMode", context.settings.photoMode)
            put("recipe", Json.encodeToJsonElement(recipe.toResponse()).jsonObject.let { value ->
                JsonObject(value + ("summary" to JsonObject(value.getValue("summary").jsonObject - "babies" - "id")))
            })
        }
        return json.decodeFromString<VisualPlan>(
            runAgent(
                "visual-planning", resources.visualPlanning,
                resources.imagePlanSchema, context.settings.modelId, input.toString()
            )
        ).images.map {
            require(it.prompt.isNotBlank() && it.altText.isNotBlank())
            PlannedImage(it.instructionId, resources.rendering + "\n\nEscena específica:\n" + it.prompt, it.altText)
        }
    }

    private suspend fun runAgent(
        name: String,
        instructions: String,
        schema: JsonObject,
        modelId: String,
        input: String
    ): String {
        val model = LLModel(
            provider = LLMProvider.OpenAI,
            id = modelId,
            capabilities = listOf(
                LLMCapability.Completion,
                LLMCapability.Schema.JSON.Standard,
                LLMCapability.OpenAIEndpoint.Responses,
            )
        )
        val params = OpenAIResponsesParams(
            maxTokens = 12000,
            store = false,
            promptCacheKey = resources.cacheKey(name, instructions, schema),
            schema = LLMParams.Schema.JSON.Standard(name, schema)
        )
        val agent = FunctionalAIAgent<String, String>(
            executor,
            AIAgentConfig(prompt(name, params) { system(instructions) }, model, maxAgentIterations = 1),
            functionalStrategy(name) { value ->
                llm.writeSession {
                    appendPrompt { user(value) }
                    requestLLMWithoutTools().parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }
                }
            },
        )
        try {
            return agent.run(input)
        } finally {
            agent.close()
        }
    }

    override fun close() = executor.close()
}
