package com.elceller.bebilo.recipes

import com.elceller.bebilo.recipes.data.repository.FirestoreRecipeRepository
import com.elceller.bebilo.recipes.domain.model.*
import com.google.cloud.firestore.*
import io.grpc.Status
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.Executor

@OptIn(ExperimentalCoroutinesApi::class)
class RecipeStatusRepositoryTest {
    private val firestore = mockk<Firestore>()
    private val collection = mockk<CollectionReference>()
    private val listener = mockk<ListenerRegistration>(relaxed = true)
    private val callback = slot<EventListener<QuerySnapshot>>()
    private val repository = FirestoreRecipeRepository(firestore)

    init {
        every { firestore.collection("users").document("owner").collection("recipes") } returns collection
        every { collection.addSnapshotListener(any<Executor>(), capture(callback)) } returns listener
    }
    private fun snapshot(vararg data: Map<String, Any?>) = mockk<QuerySnapshot>().also { snapshot ->
        every { snapshot.documents } returns data.map { value -> mockk<QueryDocumentSnapshot>().also {
            every { it.data } returns value
        } }
    }

    @Test fun `each observer has its own listener which is removed on cancellation`() = runTest {
        val seen = mutableListOf<List<RecipeStatusChange>>()
        val job = launch { repository.observeStatuses("owner").collect { seen.add(it) } }
        runCurrent()
        callback.captured.onEvent(snapshot(), null)
        runCurrent()
        assertEquals(listOf(emptyList<RecipeStatusChange>()), seen)
        callback.captured.onEvent(snapshot(storedRecipe("public", status = "Created")), null)
        runCurrent()
        callback.captured.onEvent(snapshot(storedRecipe("public", status = "Created").also { it["description"] = "Text" }), null)
        runCurrent()
        assertEquals(2, seen.size)
        assertEquals(RecipeStatusChange("public", RecipeStatus.Created), seen.last().single())
        job.cancelAndJoin()
        verify(exactly = 1) { listener.remove() }
    }

    @Test fun `invalid snapshot fails without exposing partial events and removes listener`() = runTest {
        val seen = mutableListOf<List<RecipeStatusChange>>()
        val result = async { runCatching { repository.observeStatuses("owner").collect { seen.add(it) } } }
        runCurrent()
        callback.captured.onEvent(snapshot(storedRecipe("valid"), storedRecipe("invalid").also { it.remove("status") }), null)
        runCurrent()
        assertTrue(result.await().exceptionOrNull() is InvalidStoredRecipeException)
        assertTrue(seen.isEmpty())
        verify(exactly = 1) { listener.remove() }
    }

    @Test fun `duplicate public IDs fail the subscription`() = runTest {
        val result = async { runCatching { repository.observeStatuses("owner").collect {} } }
        runCurrent()
        callback.captured.onEvent(snapshot(storedRecipe("same"), storedRecipe("same")), null)
        runCurrent()
        assertTrue(result.await().exceptionOrNull() is InvalidStoredRecipeException)
        verify(exactly = 1) { listener.remove() }
    }

    @Test fun `temporary listener failure is unavailable and releases listener`() = runTest {
        val result = async { runCatching { repository.observeStatuses("owner").collect {} } }
        runCurrent()
        val unavailable = FirestoreException.forServerRejection(Status.UNAVAILABLE, "unavailable")
        callback.captured.onEvent(null, unavailable)
        runCurrent()
        assertTrue(result.await().exceptionOrNull() is ServiceUnavailableException)
        verify(exactly = 1) { listener.remove() }
    }

    @Test fun `permanent listener failure after initial snapshot closes and removes registration`() = runTest {
        val seen = mutableListOf<List<RecipeStatusChange>>()
        val result = async { runCatching { repository.observeStatuses("owner").collect { seen.add(it) } } }
        runCurrent()
        callback.captured.onEvent(snapshot(), null)
        runCurrent()
        callback.captured.onEvent(null, FirestoreException.forServerRejection(Status.PERMISSION_DENIED, "private"))
        runCurrent()
        assertTrue(result.await().exceptionOrNull() is InternalServiceException)
        assertEquals(listOf(emptyList<RecipeStatusChange>()), seen)
        verify(exactly = 1) { listener.remove() }
    }

    @Test fun `failure registering listener is mapped before any event`() = runTest {
        every { collection.addSnapshotListener(any<Executor>(), any<EventListener<QuerySnapshot>>()) } throws
            FirestoreException.forServerRejection(Status.UNAVAILABLE, "unavailable")
        val failure = runCatching { repository.observeStatuses("owner").first() }.exceptionOrNull()
        assertTrue(failure is ServiceUnavailableException)
        verify(exactly = 0) { listener.remove() }
    }

    @Test fun `overflow closes instead of dropping and removes listener`() = runTest {
        val proceed = CompletableDeferred<Unit>()
        val first = CompletableDeferred<Unit>()
        val seen = mutableListOf<List<RecipeStatusChange>>()
        val result = async { runCatching {
            repository.observeStatuses("owner").collect {
                seen.add(it)
                first.complete(Unit)
                proceed.await()
            }
        } }
        runCurrent()
        callback.captured.onEvent(snapshot(storedRecipe(status = "Created")), null)
        runCurrent()
        first.await()
        repeat(65) { number ->
            callback.captured.onEvent(snapshot(storedRecipe(status = if (number % 2 == 0) "Loading" else "Ready")), null)
        }
        runCurrent()
        verify(exactly = 1) { listener.remove() }
        proceed.complete(Unit)
        runCurrent()
        assertTrue(result.await().exceptionOrNull() is ServiceUnavailableException)
        assertEquals(65, seen.size) // In-flight batch plus 64 queued; the overflowing batch is rejected.
    }
}
