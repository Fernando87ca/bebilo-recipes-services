package com.elceller.bebilo.recipes.domain.usecase

import com.elceller.bebilo.recipes.domain.model.*
import com.elceller.bebilo.recipes.domain.repository.AuthUserRepository
import com.elceller.bebilo.recipes.domain.repository.RecipeRepository

class GetRecipesUseCase(
    private val auth: AuthUserRepository,
    private val repository: suspend () -> RecipeRepository,
) {
    suspend operator fun invoke(token: String): List<Recipe> {
        if (token.isBlank()) throw UnauthorizedException()
        val uid = auth.getUserUid(token)
        return repository().getAll(uid).also { recipes ->
            recipes.forEach(Recipe::validateStoredContent)
            if (recipes.map { it.summary.id }.distinct().size != recipes.size) throw InvalidStoredRecipeException()
        }
    }
}

class GetRecipeUseCase(
    private val auth: AuthUserRepository,
    private val repository: suspend () -> RecipeRepository,
) {
    suspend operator fun invoke(token: String, recipeId: String): Recipe {
        if (token.isBlank()) throw UnauthorizedException()
        val uid = auth.getUserUid(token)
        if (recipeId.isBlank()) throw ValidationException()
        val recipe = repository().get(uid, recipeId) ?: throw RecipeNotFoundException()
        recipe.validateStoredContent()
        if (recipe.summary.id != recipeId) throw InvalidStoredRecipeException()
        return recipe
    }
}
