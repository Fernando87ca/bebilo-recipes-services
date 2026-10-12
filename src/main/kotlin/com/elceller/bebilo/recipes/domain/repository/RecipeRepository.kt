package com.elceller.bebilo.recipes.domain.repository

import com.elceller.bebilo.recipes.domain.model.Recipe

interface RecipeRepository {
    suspend fun getAll(ownerUid: String): List<Recipe>
    suspend fun get(ownerUid: String, recipeId: String): Recipe?
}
