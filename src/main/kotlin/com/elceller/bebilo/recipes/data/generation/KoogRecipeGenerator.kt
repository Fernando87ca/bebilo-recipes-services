package com.elceller.bebilo.recipes.data.generation

import ai.koog.agents.core.agent.AIAgentTool.AgentToolInput
import ai.koog.agents.core.agent.FunctionalAIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.createAgentTool
import ai.koog.agents.core.agent.functionalStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
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
import com.elceller.bebilo.recipes.domain.model.*
import com.elceller.bebilo.recipes.domain.repository.RecipeGenerator
import com.elceller.bebilo.recipes.domain.usecase.ingredientAllergens
import com.elceller.bebilo.recipes.domain.usecase.validateMaintainedIngredient
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
    private val report: (String) -> Unit = {},
) : RecipeGenerator, AutoCloseable {

    private val executor = MultiLLMPromptExecutor(client)
    private val json = Json { ignoreUnknownKeys = false }

    @OptIn(InternalAgentToolsApi::class)
    override suspend fun compose(context: GenerationContext, initial: Recipe): Recipe {
        val analysisTool = service<SafetyInput, AllergenAnalysis>(
            "allergen-analysis", resources.allergenAnalysis,
            resources.analysisSchema, context.settings.modelId
        ).createAgentTool(
            "analyze_allergens", "Analiza todos los alérgenos y restricciones y verifica la receta final"
        )
        val alternativesTool = service<AlternativesInput, IngredientAlternatives>(
            "ingredient-alternatives",
            resources.ingredientAlternatives, resources.alternativesSchema, context.settings.modelId
        ).createAgentTool(
            "find_ingredient_alternatives", "Busca sustitutos compatibles u omisiones culinariamente válidas"
        )
        val registry = ToolRegistry { tool(analysisTool); tool(alternativesTool) }
        val orchestrator = FunctionalAIAgent<GenerationContext, Recipe>(
            executor,
            config(
                "recipe-orchestrator",
                resources.composition,
                resources.stepsSchema,
                context.settings.modelId,
                iterations = 10
            ),
            functionalStrategy("recipe-orchestrator") { value ->
                // Typed native agent-tool calls are mandatory phases, not optional LLM decisions.
                suspend fun analyze(input: SafetyInput): AllergenAnalysis = phase("ANALYZE_ALLERGENS") {
                    check(registry.getToolOrNull("analyze_allergens") === analysisTool)
                    val result = analysisTool.execute(AgentToolInput(input))
                    check(result.successful) { "Allergen tool failed" }
                    requireNotNull(result.result).also { validateAnalysis(input, it) }
                }

                val candidates = phase("INGREDIENT_CANDIDATES") {
                    json.decodeFromString<CandidateIngredients>(
                        runAgent(
                            "ingredient-candidates",
                            resources.ingredientCandidates, resources.candidateSchema, value.settings.modelId,
                            culinaryContext(value).toString()
                        )
                    )
                }
                require(candidates.ingredients.size >= initial.ingredients.size)
                require(candidates.roles.map { it.index }.sorted() == candidates.ingredients.indices.toList())
                require(candidates.roles.all { it.role.isNotBlank() })
                candidates.ingredients.take(initial.ingredients.size).forEachIndexed { index, ingredient ->
                    validateMaintainedIngredient(initial.ingredients[index], ingredient.toDomain())
                }
                require(candidates.ingredients.drop(initial.ingredients.size).all { it.catalogId == null })
                require(candidates.ingredients.all { it.quantity != null && it.quantity > 0 && !it.unit.isNullOrBlank() })
                val input = SafetyInput(
                    candidates.ingredients, candidates.roles, value.culinaryBabies(),
                    initial.cookingMethods, value.request.title, null
                )
                val first = analyze(input)
                val conflicts = first.ingredients.filter { !it.compatible }
                val changes = if (conflicts.isEmpty()) emptyList() else phase("FIND_INGREDIENT_ALTERNATIVES") {
                    check(registry.getToolOrNull("find_ingredient_alternatives") === alternativesTool)
                    val result = alternativesTool.execute(AgentToolInput(AlternativesInput(input, first)))
                    check(result.successful) { "Alternative tool failed" }
                    requireNotNull(result.result).changes.also { proposals ->
                        require(proposals.map { it.index }.sorted() == conflicts.map { it.index }.sorted())
                        proposals.forEach { proposal ->
                            val conflict = conflicts.single { it.index == proposal.index }
                            require(proposal.reason.isNotBlank())
                            when (proposal.action) {
                                "omission" -> require(proposal.replacementIngredient == null && conflict.canOmit && proposal.omissionPreservesRecipe)
                                "replacement" -> {
                                    val replacement = requireNotNull(proposal.replacementIngredient)
                                    require(replacement.catalogId == null && replacement.category.name != "Custom")
                                    require(
                                        replacement.name.isNotBlank() && replacement.quantity != null && replacement.quantity > 0 &&
                                                replacement.quantity.isFinite() && !replacement.unit.isNullOrBlank()
                                    )
                                }

                                else -> error("Invalid alternative action")
                            }
                        }
                    }
                }
                val changeByIndex = changes.associateBy { it.index }
                val updated = candidates.ingredients.mapIndexedNotNull { index, ingredient ->
                    val change = changeByIndex[index]
                    if (change != null) change.replacementIngredient else ingredient.copy(
                        allergens = AllergenGroups.merge(
                            ingredient.allergens,
                            first.ingredients.single { it.index == index }.allergens
                        )
                    )
                }
                require(updated.isNotEmpty())
                val updatedRoles = candidates.roles.sortedBy { it.index }.filter {
                    changeByIndex[it.index]?.action != "omission"
                }.mapIndexed { index, role -> role.copy(index = index) }
                val omitted = changes.filter { it.action == "omission" }.map { candidates.ingredients[it.index] }
                val updatedInput = input.copy(ingredients = updated, roles = updatedRoles, omittedIngredients = omitted)
                val approvedAnalysis = if (changes.isEmpty()) first else analyze(updatedInput)
                require(approvedAnalysis.ingredients.all { it.compatible })
                val declaredNames = initial.ingredients.filterIndexed { index, _ -> changeByIndex[index] == null }
                    .flatMap { it.allergens }.associate { it.code to it.name }
                val approved = updated.mapIndexed { index, ingredient ->
                    ingredient.copy(
                        allergens =
                            AllergenGroups.merge(
                                ingredient.allergens,
                                approvedAnalysis.ingredients.single { it.index == index }.allergens
                            )
                                .map { it.copy(name = declaredNames[it.code] ?: it.name) })
                }
                // Newly inferred entries share a retained caller label for the same code, without rewriting declarations.
                val publicChanges = changes.filter { it.index < initial.ingredients.size }.map { change ->
                    val replacement = if (change.action == "omission") null else {
                        val position = candidates.ingredients.indices.take(change.index)
                            .count { changeByIndex[it]?.action != "omission" }
                        approved[position].toDomain()
                    }
                    RecipeIngredientChange(
                        if (change.action == "omission") IngredientChangeAction.Omission else IngredientChangeAction.Replacement,
                        change.index, initial.ingredients[change.index], replacement, change.reason
                    )
                }
                val compositionInput = culinaryContext(value).toMutableMap().apply {
                    put("approvedIngredients", Json.encodeToJsonElement(approved))
                    put("ingredientChanges", Json.encodeToJsonElement(changes))
                    put("requestedTitleCompatible", JsonPrimitive(approvedAnalysis.requestedTitleCompatible))
                }.let(::JsonObject)
                val recipe = phase("COMPOSITION") {
                    composeApproved(value, initial, compositionInput.toString()).also {
                        require(it.ingredients == approved.map { ingredient -> ingredient.toDomain() })
                        require(changes.isEmpty() || !it.adaptationNotes.isNullOrBlank())
                    }
                }
                val withChanges = recipe.copy(ingredientChanges = publicChanges)
                val finalInput = updatedInput.copy(ingredients = approved, recipe = culinaryRecipe(withChanges))
                val final = phase("FINAL_VERIFICATION") {
                    analyze(finalInput).also { analysis ->
                        require(analysis.recipeCompatible && analysis.ingredients.all { it.compatible })
                        analysis.ingredients.forEach { record ->
                            require(
                                AllergenGroups.merge(
                                    approved[record.index].allergens,
                                    record.allergens
                                ) == approved[record.index].allergens
                            )
                        }
                    }
                }
                withChanges.copy(
                    safetyVerification = RecipeSafetyVerification(
                    withChanges,
                    value.babies.map { it.copy(name = "") }, final.requestedTitleCompatible
                )
                )
            }, toolRegistry = registry
        )
        try {
            return orchestrator.run(context)
        } finally {
            orchestrator.close()
        }
    }

    private fun culinaryContext(context: GenerationContext): JsonObject {
        val persisted = context.toStorageMap().toJsonValue().jsonObject
        return JsonObject(
            persisted + mapOf(
                "babies" to Json.encodeToJsonElement(context.culinaryBabies()),
                "request" to JsonObject(persisted.getValue("request").jsonObject - "babyIds")
            )
        )
    }

    private fun culinaryRecipe(recipe: Recipe): GeneratedRecipe {
        val dto = recipe.toResponse()
        return GeneratedRecipe(
            dto.summary.title,
            dto.ingredients,
            dto.cookingMethods,
            dto.instructions,
            dto.description,
            dto.preparationMinutes,
            dto.cookingMinutes,
            dto.servings,
            dto.storageNotes,
            dto.adaptationNotes,
            dto.summary.recommendedAgeMonths,
            dto.summary.totalMinutes,
            dto.summary.texture,
            dto.summary.tags
        )
    }

    private suspend fun <T> phase(name: String, block: suspend () -> T): T {
        val start = System.nanoTime()
        try {
            val result = block()
            report("$name OK durationMs=${(System.nanoTime() - start) / 1_000_000}")
            return result
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            report("$name FAILED durationMs=${(System.nanoTime() - start) / 1_000_000}")
            throw IllegalStateException("$name failed")
        }
    }

    private inline fun <reified I, reified O> service(
        name: String, instructions: String, schema: JsonObject,
        modelId: String
    ): ClosingAgentService<I, O> = ClosingAgentService(
        executor, config(name, instructions, schema, modelId),
        functionalStrategy(name) { input: I ->
            val output = llm.writeSession {
                val payload = Json.encodeToJsonElement(input).jsonObject
                appendPrompt { user(JsonObject(payload + ("allergenGroups" to Json.encodeToJsonElement(AllergenGroups.names))).toString()) }
                requestLLMWithoutTools().parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }
            }
            json.decodeFromString<O>(output)
        })

    private suspend fun composeApproved(context: GenerationContext, initial: Recipe, input: String): Recipe {
        val schema =
            if (context.settings.instructionStyle == "steps") resources.stepsSchema else resources.narrativeSchema
        val result = json.decodeFromString<GeneratedRecipe>(
            runAgent(
                "composition", resources.composition,
                schema, context.settings.modelId, input
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
            adaptationNotes = result.adaptationNotes
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
        val agent = FunctionalAIAgent<String, String>(
            executor,
            config(name, instructions, schema, modelId),
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

    private fun config(
        name: String,
        instructions: String,
        schema: JsonObject,
        modelId: String,
        iterations: Int = 1
    ): AIAgentConfig {
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
        return AIAgentConfig(prompt(name, params) { system(instructions) }, model, maxAgentIterations = iterations)
    }

    override fun close() = executor.close()
}
