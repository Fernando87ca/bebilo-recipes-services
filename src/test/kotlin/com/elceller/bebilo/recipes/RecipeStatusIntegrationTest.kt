package com.elceller.bebilo.recipes

import com.elceller.bebilo.recipes.data.repository.FirestoreRecipeRepository
import com.elceller.bebilo.recipes.domain.model.*
import com.google.cloud.firestore.FirestoreOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class RecipeStatusIntegrationTest {
    @Test fun `external writes notify independent listeners isolate accounts and reconnect with current state`() = runBlocking {
        val store = FirestoreOptions.newBuilder().setProjectId("demo-bebilo-recipes").build().service
        val writer = FirestoreOptions.newBuilder().setProjectId("demo-bebilo-recipes").build().service
        val uid = UUID.randomUUID().toString()
        val own = writer.collection("users").document(uid).collection("recipes").document("physical")
        val other = writer.collection("users").document("$uid-other").collection("recipes").document("physical")
        val repository = FirestoreRecipeRepository(store)
        val id = "one/two ?#%"
        fun subscription(owner: String): Pair<Channel<List<RecipeStatusChange>>, Job> {
            val channel = Channel<List<RecipeStatusChange>>(Channel.UNLIMITED)
            val job = launch { repository.observeStatuses(owner).collect { channel.send(it) } }
            return channel to job
        }
        val (a, jobA) = subscription(uid)
        val (b, jobB) = subscription(uid)
        val (private, jobPrivate) = subscription("$uid-other")
        try {
            withTimeout(10000) {
                assertEquals(emptyList<RecipeStatusChange>(), a.receive())
                assertEquals(emptyList<RecipeStatusChange>(), b.receive())
                assertEquals(emptyList<RecipeStatusChange>(), private.receive())
                other.set(storedRecipe("private", status = "Created")).get()
                assertEquals(listOf(RecipeStatusChange("private", RecipeStatus.Created)), private.receive())
                for (status in listOf("Created", "Loading", "Ready", "Loading", "Error")) {
                    own.set(storedRecipe(id, status = status)).get()
                    val expected = listOf(RecipeStatusChange(id, RecipeStatus.valueOf(status)))
                    assertEquals(expected, a.receive())
                    assertEquals(expected, b.receive())
                }
                assertTrue(private.tryReceive().isFailure)
                jobA.cancelAndJoin()
                assertEquals(listOf(RecipeStatusChange(id, RecipeStatus.Error)), repository.observeStatuses(uid).first())
                own.update("description", "Updated content").get()
                // A subsequent different state acts as an ordering barrier for the content-only write.
                own.update("status", "Ready").get()
                assertEquals(listOf(RecipeStatusChange(id, RecipeStatus.Ready)), b.receive())
                assertTrue(b.tryReceive().isFailure)
            }
        } finally {
            jobA.cancelAndJoin(); jobB.cancelAndJoin(); jobPrivate.cancelAndJoin()
            own.delete().get(); other.delete().get()
            writer.close(); repository.close()
        }
    }
}
