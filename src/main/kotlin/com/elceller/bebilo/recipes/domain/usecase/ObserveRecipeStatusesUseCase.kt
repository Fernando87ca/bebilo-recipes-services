package com.elceller.bebilo.recipes.domain.usecase

import com.elceller.bebilo.recipes.domain.model.RecipeStatusChange
import com.elceller.bebilo.recipes.domain.model.UnauthorizedException
import com.elceller.bebilo.recipes.domain.repository.AuthUserRepository
import com.elceller.bebilo.recipes.domain.repository.RecipeStatusRepository
import kotlinx.coroutines.flow.Flow

class ObserveRecipeStatusesUseCase(
    private val auth: AuthUserRepository,
    private val repository: suspend () -> RecipeStatusRepository,
) {
    suspend operator fun invoke(token: String): Flow<List<RecipeStatusChange>> {
        if (token.isBlank()) throw UnauthorizedException()
        val uid = auth.getUserUid(token)
        return repository().observeStatuses(uid)
    }
}
