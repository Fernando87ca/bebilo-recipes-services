package com.elceller.bebilo.recipes.data.migration

import com.elceller.bebilo.recipes.domain.model.*
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Pure inventory: does not fetch, upload, or modify either Firestore or Storage. */
object LegacyImageMigration {
    data class Target(val oldUrl: String, val path: String)

    fun targets(recipe: Recipe, uid: String, documentId: String, references: Map<*, *>, publicBaseUrl: String): List<Target> {
        recipe.validateStoredContent()
        val base = URI(publicBaseUrl)
        val prefix = base.rawPath.trimEnd('/') + "/v1/recipe/"
        return images(recipe).mapNotNull { image ->
            val uri = URI(image.url)
            // New Firebase URLs and unrelated historical external images are left untouched.
            if (!uri.rawPath.contains("/v1/recipe/") || !uri.rawPath.contains("/images/")) return@mapNotNull null
            require(uri.scheme == base.scheme && uri.authority == base.authority && uri.rawPath.startsWith(prefix))
            require(uri.rawQuery == null && uri.rawFragment == null)
            val segments = uri.rawPath.removePrefix(prefix).split('/')
            require(segments.size == 3 && segments[1] == "images")
            fun decode(value: String) = URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8)
            require(decode(segments[0]) == recipe.summary.id)
            val imageId = decode(segments[2])
            require(imageId.isNotBlank() && '/' !in imageId && uid.isNotBlank() && documentId.isNotBlank())
            val path = references[imageId] as? String ?: error("Missing image reference")
            require(path == "recipe-images/$uid/$documentId/$imageId.jpg")
            Target(image.url, path)
        }.distinct()
    }

    fun replace(recipe: Recipe, urls: Map<String, String>): Recipe {
        fun image(value: RecipeImage?) = value?.let { it.copy(url = urls[it.url] ?: it.url) }
        return recipe.copy(summary = recipe.summary.copy(coverImage = image(recipe.summary.coverImage)),
            instructions = when (val instructions = recipe.instructions) {
                is RecipeInstructions.Steps -> instructions.copy(steps = instructions.steps.map { it.copy(image = image(it.image)) })
                is RecipeInstructions.Narrative -> instructions.copy(paragraphs = instructions.paragraphs.map { it.copy(image = image(it.image)) })
            }).also(Recipe::validateStoredContent)
    }

    private fun images(recipe: Recipe): List<RecipeImage> = listOfNotNull(recipe.summary.coverImage) + when (val instructions = recipe.instructions) {
        is RecipeInstructions.Steps -> instructions.steps.mapNotNull { it.image }
        is RecipeInstructions.Narrative -> instructions.paragraphs.mapNotNull { it.image }
    }
}
