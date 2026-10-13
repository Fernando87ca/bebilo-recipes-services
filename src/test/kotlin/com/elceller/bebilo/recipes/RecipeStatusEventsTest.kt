package com.elceller.bebilo.recipes

import com.elceller.bebilo.recipes.api.routes.streamRecipeStatuses
import com.elceller.bebilo.recipes.api.response.*
import com.elceller.bebilo.recipes.data.mapper.toRecipe
import com.elceller.bebilo.recipes.data.repository.RecipeStatusTracker
import com.elceller.bebilo.recipes.domain.model.*
import com.elceller.bebilo.recipes.domain.repository.*
import com.elceller.bebilo.recipes.domain.usecase.ObserveRecipeStatusesUseCase
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.plugins.di.provide
import io.ktor.server.routing.*
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class RecipeStatusEventsTest {
    private val auth = object : AuthUserRepository {
        override suspend fun getUserUid(bearerToken: String): String = when (bearerToken) {
            "token" -> "owner"
            "forbidden" -> throw ForbiddenException()
            else -> throw UnauthorizedException()
        }
    }
    private fun change(status: RecipeStatus) = RecipeStatusChange("one/two ?#%", status)

    @Test fun `authentication precedes lazy observation`() = runTest {
        var owner: String? = null
        val useCase = ObserveRecipeStatusesUseCase(auth) {
            object : RecipeStatusRepository {
                override fun observeStatuses(ownerUid: String): Flow<List<RecipeStatusChange>> {
                    owner = ownerUid
                    return flowOf(emptyList())
                }
            }
        }
        for (token in listOf("", "invalid")) {
            try { useCase(token); fail("accepted invalid token") } catch (_: UnauthorizedException) { }
            assertNull(owner)
        }
        useCase("token")
        assertEquals("owner", owner)
    }

    @Test fun `tracker validates complete snapshot and emits only additions or changed statuses`() {
        val tracker = RecipeStatusTracker()
        fun value(status: String) = storedRecipe("one/two ?#%", status = status).toRecipe()
        assertEquals(emptyList<RecipeStatusChange>(), tracker.accept(emptyList()))
        assertEquals(listOf(change(RecipeStatus.Created)), tracker.accept(listOf(value("Created"))))
        assertNull(tracker.accept(listOf(value("Created").copy(description = "New text"))))
        for (status in listOf("Loading", "Ready", "Error", "Loading", "Ready")) {
            assertEquals(listOf(change(RecipeStatus.valueOf(status))), tracker.accept(listOf(value(status))))
        }
        assertNull(tracker.accept(emptyList()))
        assertEquals(listOf(change(RecipeStatus.Ready)), tracker.accept(listOf(value("Ready"))))
        assertThrows(InvalidStoredRecipeException::class.java) { tracker.accept(listOf(value("Ready"), value("Ready"))) }
        assertThrows(InvalidStoredRecipeException::class.java) {
            tracker.accept(listOf(value("Error"), value("Ready").copy(summary = value("Ready").summary.copy(id = " "))))
        }
        assertNull(tracker.accept(listOf(value("Ready")))) // Invalid batch did not alter tracking state.
    }

    @Test fun `SSE preflight errors retain JSON status and do not resolve unauthenticated storage`() = testApplication {
        application { module {
            provide<AuthUserRepository> { auth }
            provide<RecipeStatusRepository> { error("must not resolve storage") }
        } }
        for ((token, status) in listOf(null to HttpStatusCode.Unauthorized, "invalid" to HttpStatusCode.Unauthorized,
            "forbidden" to HttpStatusCode.Forbidden)) {
            val response = client.get("/v1/recipes/events") { if (token != null) bearerAuth(token) }
            assertEquals(status, response.status)
            assertTrue(response.contentType()?.match(ContentType.Application.Json) == true)
        }
    }

    @Test fun `initial invalid document and dependency failures are JSON before stream`() = testApplication {
        application { module { provide<AuthUserRepository> { auth }; provide<RecipeStatusRepository> {
            object : RecipeStatusRepository {
                override fun observeStatuses(ownerUid: String) = flow<List<RecipeStatusChange>> { throw InvalidStoredRecipeException() }
            }
        } } }
        val response = client.get("/v1/recipes/events") { bearerAuth("token") }
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals("INTERNAL_ERROR", Json.decodeFromString<ApiErrorResponse>(response.bodyAsText()).code)
    }

    @Test fun `dependency failure before first snapshot remains unavailable JSON`() = testApplication {
        application { module { provide<AuthUserRepository> { auth }; provide<RecipeStatusRepository> {
            object : RecipeStatusRepository {
                override fun observeStatuses(ownerUid: String) = flow<List<RecipeStatusChange>> { throw ServiceUnavailableException() }
            }
        } } }
        val response = client.get("/v1/recipes/events") { bearerAuth("token") }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("SERVICE_UNAVAILABLE", Json.decodeFromString<ApiErrorResponse>(response.bodyAsText()).code)
    }

    @Test fun `timeout cleans up subscription and returns unavailable JSON`() = testApplication {
        val cleaned = CompletableDeferred<Unit>()
        application {
            module()
            routing { get("/timeout") {
                call.streamRecipeStatuses(flow { try { awaitCancellation() } finally { cleaned.complete(Unit) } },
                    initialTimeout = 30.milliseconds)
            } }
        }
        val response = client.get("/timeout")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("SERVICE_UNAVAILABLE", Json.decodeFromString<ApiErrorResponse>(response.bodyAsText()).code)
        withTimeout(2000) { cleaned.await() }
    }

    @Test fun `wire events contain only public id and status and post-start errors close cleanly`() = testApplication {
        val cleaned = CompletableDeferred<Unit>()
        application { module { provide<AuthUserRepository> { auth }; provide<RecipeStatusRepository> {
            object : RecipeStatusRepository {
                override fun observeStatuses(ownerUid: String) = flow {
                    try {
                        for (status in RecipeStatus.entries) emit(listOf(change(status)))
                        throw ServiceUnavailableException()
                    } finally { cleaned.complete(Unit) }
                }
            }
        } } }
        val response = client.get("/v1/recipes/events") { bearerAuth("token") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.contentType()?.match(ContentType.Text.EventStream) == true)
        assertEquals("private, no-store", response.headers[HttpHeaders.CacheControl])
        val body = response.bodyAsText()
        val data = body.lineSequence().filter { it.startsWith("data:") }.map { Json.parseToJsonElement(it.substringAfter(':').trim()).jsonObject }.toList()
        assertEquals(RecipeStatus.entries.map { it.name }, data.map { it.getValue("status").jsonPrimitive.content })
        data.forEach {
            assertEquals(setOf("id", "status"), it.keys)
            assertEquals("one/two ?#%", it.getValue("id").jsonPrimitive.content)
        }
        assertEquals(4, body.lineSequence().count { it.trim() == "event: recipe-status" })
        assertFalse(body.contains("SERVICE_UNAVAILABLE"))
        assertFalse(body.lineSequence().any { it.startsWith("id:") })
        withTimeout(2000) { cleaned.await() }
    }

    @Test fun `empty initial batch stays connected heartbeat is comment and disconnect cancels observer`() = testApplication {
        val cleaned = CompletableDeferred<Unit>()
        application {
            module()
            routing { get("/heartbeat") {
                call.streamRecipeStatuses(flow {
                    try { emit(emptyList()); awaitCancellation() } finally { cleaned.complete(Unit) }
                }, heartbeatPeriod = 30.milliseconds)
            } }
        }
        client.prepareGet("/heartbeat").execute { response ->
            assertEquals(HttpStatusCode.OK, response.status)
            val channel = response.bodyAsChannel()
            withTimeout(2000) { assertEquals(": heartbeat", channel.readLine()) }
            channel.cancel()
        }
        withTimeout(2000) { cleaned.await() }
    }
}
