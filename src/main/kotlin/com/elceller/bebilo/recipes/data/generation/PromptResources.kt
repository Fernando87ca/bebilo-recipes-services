package com.elceller.bebilo.recipes.data.generation

import kotlinx.serialization.json.*
import java.security.MessageDigest

class PromptResources {
    val composition = resource("composition.txt")
    val visualPlanning = resource("visual-planning.txt")
    val rendering = resource("image-rendering.txt")
    val stepsSchema = Json.parseToJsonElement(resource("recipe-steps.schema.json")).jsonObject
    val narrativeSchema = Json.parseToJsonElement(resource("recipe-narrative.schema.json")).jsonObject
    val imagePlanSchema = Json.parseToJsonElement(resource("image-plan.schema.json")).jsonObject

    fun cacheKey(agent: String, prompt: String, schema: JsonObject): String = "bebilo-$agent-" +
        MessageDigest.getInstance("SHA-256").digest((prompt + schema.toString()).toByteArray()).take(12)
            .joinToString("") { "%02x".format(it) }

    private fun resource(name: String): String = requireNotNull(javaClass.getResourceAsStream("/prompts/$name")) {
        "Missing prompt resource: $name"
    }.bufferedReader().use { it.readText() }.also { require(it.isNotBlank()) }
}
