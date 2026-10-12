package com.elceller.bebilo.recipes.domain.usecase

import com.elceller.bebilo.recipes.domain.model.*
import com.elceller.bebilo.recipes.domain.repository.*

class GetRecipeImageUseCase(
    private val auth: AuthUserRepository,
    private val repository: suspend () -> RecipeWriteRepository,
    private val storage: suspend () -> RecipeImageStorage,
) {
    suspend operator fun invoke(token: String, recipeId: String, imageId: String): ByteArray {
        val uid = auth.getUserUid(token)
        if (recipeId.isBlank() || imageId.isBlank()) throw ValidationException()
        val path = repository().imagePath(uid, recipeId, imageId) ?: throw RecipeNotFoundException()
        return storage().read(path)
    }
}
