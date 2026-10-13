package com.elceller.bebilo.recipes.data.repository

import com.elceller.bebilo.recipes.domain.model.*

/** Each listener owns its state; validation completes before any event is exposed. */
internal class RecipeStatusTracker {
    private var previous: Map<String, RecipeStatus>? = null

    fun accept(recipes: List<Recipe>): List<RecipeStatusChange>? {
        recipes.forEach(Recipe::validateStoredContent)
        val current = recipes.associate { it.summary.id to it.status }
        if (current.size != recipes.size) throw InvalidStoredRecipeException()
        val changes = current.filter { (id, status) -> previous?.get(id) != status }
            .map { (id, status) -> RecipeStatusChange(id, status) }
        val first = previous == null
        previous = current
        return changes.takeIf { first || it.isNotEmpty() }
    }
}
