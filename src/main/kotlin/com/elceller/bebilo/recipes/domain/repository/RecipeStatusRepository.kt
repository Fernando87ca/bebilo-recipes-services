package com.elceller.bebilo.recipes.domain.repository

import com.elceller.bebilo.recipes.domain.model.RecipeStatusChange
import kotlinx.coroutines.flow.Flow

interface RecipeStatusRepository {
    /** First batch is the complete current state (possibly empty), followed by additions/status changes. */
    fun observeStatuses(ownerUid: String): Flow<List<RecipeStatusChange>>
}
