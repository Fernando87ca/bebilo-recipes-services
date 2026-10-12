package com.elceller.bebilo.recipes.domain.repository

import com.elceller.bebilo.recipes.domain.model.*

interface CreationContextRepository {
    suspend fun settings(token: String): AiSettings
    suspend fun babies(token: String): List<GenerationBaby>
}

interface RecipeWriteRepository {
    suspend fun create(ownerUid: String, recipe: Recipe, context: GenerationContext): String
    suspend fun save(ownerUid: String, documentId: String, recipe: Recipe, images: Map<String, String> = emptyMap())
    suspend fun imagePath(ownerUid: String, recipeId: String, imageId: String): String?
}

interface RecipeGenerator {
    suspend fun compose(context: GenerationContext, initial: Recipe): Recipe
    suspend fun planImages(context: GenerationContext, recipe: Recipe): List<PlannedImage>
}

interface RecipeImageGenerator { suspend fun generate(prompt: String): ByteArray }
interface RecipeImageStorage {
    suspend fun upload(ownerUid: String, documentId: String, imageId: String, bytes: ByteArray): String
    suspend fun read(path: String): ByteArray
}

fun interface GenerationDispatcher { fun submit(job: GenerationJob) }
