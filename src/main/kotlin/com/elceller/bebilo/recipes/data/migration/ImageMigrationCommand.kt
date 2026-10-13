package com.elceller.bebilo.recipes.data.migration

import com.elceller.bebilo.recipes.config.AppConfig
import com.elceller.bebilo.recipes.data.mapper.toRecipe
import com.elceller.bebilo.recipes.data.mapper.toStorageMap
import com.elceller.bebilo.recipes.data.repository.FirebaseDownloadUrls
import com.elceller.bebilo.recipes.data.repository.FirebaseRecipeImageStorage
import com.elceller.bebilo.recipes.data.repository.FirestoreRecipeRepository
import com.google.cloud.firestore.Precondition

/** Explicit offline operation. Output contains counts only, never download URLs or account data. */
fun migrateImageUrls(config: AppConfig, apply: Boolean): Int {
    val repository = FirestoreRecipeRepository.fromConfig(config)
    try {
        val photos = FirebaseRecipeImageStorage.fromConfig(config)
        val bucketAvailable = try { photos.storage.get(photos.bucket) != null } catch (failure: Exception) {
            reportMigrationIssue("bucket", failure); false
        }
        println("mode=${if (apply) "apply" else "inventory"} bucketAvailable=$bucketAvailable")
        var scanned = 0; var legacy = 0; var changed = 0; var issues = 0
        val documents = repository.firestore.collectionGroup("recipes").get().get().documents
        val identities = mutableSetOf<Pair<String, String>>()
        // Preflight the complete inventory before apply; duplicate identities abort all writes.
        val candidates = documents.mapNotNull { document ->
            scanned++
            try {
                val parts = document.reference.path.split('/')
                require(parts.size == 4 && parts[0] == "users" && parts[2] == "recipes")
                val recipe = document.data.toRecipe()
                require(identities.add(parts[1] to recipe.summary.id))
                val targets = LegacyImageMigration.targets(recipe, parts[1], document.id,
                    document.get("generationImages") as? Map<*, *> ?: emptyMap<String, String>(), config.publicBaseUrl)
                if (targets.isEmpty()) null else Triple(document, recipe, targets).also { legacy++ }
            } catch (failure: Exception) { issues++; reportMigrationIssue("inventory", failure); null }
        }
        if (!apply || issues == 0) for ((document, recipe, targets) in candidates) {
            var stage = "object-read"
            try {
                require(bucketAvailable)
                val objects = targets.associateWith { target ->
                    photos.storage.get(photos.bucket, target.path)?.also { require(it.contentType == "image/jpeg") }
                        ?: error("Missing object")
                }
                if (apply) {
                    stage = "object-metadata"
                    val urls = objects.map { (target, blob) ->
                        target.oldUrl to FirebaseDownloadUrls.ensureUrl(photos.storage, blob)
                    }.toMap()
                    val updated = LegacyImageMigration.replace(recipe, urls).toStorageMap()
                    // Do not overwrite a worker/client update that happened after the inventory snapshot.
                    stage = "document-update"
                    document.reference.update(mapOf("summary" to updated["summary"], "instructions" to updated["instructions"]),
                        Precondition.updatedAt(document.updateTime!!)).get()
                    changed++
                }
            } catch (failure: Exception) { issues++; reportMigrationIssue(stage, failure) }
        }
        println("scanned=$scanned legacyRecipes=$legacy changed=$changed issues=$issues")
        return if (issues == 0) 0 else 2
    } finally { repository.close() }
}

private fun reportMigrationIssue(stage: String, failure: Exception) {
    val cause = if (failure is java.util.concurrent.ExecutionException) failure.cause ?: failure else failure
    val code = (cause as? com.google.cloud.storage.StorageException)?.code
    println("issue=$stage type=${cause.javaClass.simpleName}${code?.let { " status=$it" } ?: ""}")
}
