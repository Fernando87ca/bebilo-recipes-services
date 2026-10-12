package com.elceller.bebilo.recipes

import com.elceller.bebilo.recipes.data.repository.AuthServicesUserRepository
import com.elceller.bebilo.recipes.domain.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException

class AuthServicesUserRepositoryTest {
    @Test fun `identity is verified by Auth with the same bearer and body token`() = runTest {
        val client = client { request ->
            assertEquals("http://auth/v1/user-info", request.url.toString())
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("Bearer token", request.headers[HttpHeaders.Authorization])
            assertTrue(request.body.toByteArray().decodeToString().contains("\"token\":\"token\""))
            respond("{\"uid\":\"account-a\",\"email\":\"ignored@example.com\"}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        client.use { assertEquals("account-a", AuthServicesUserRepository("http://auth/v1", it).getUserUid("token")) }
    }
    @Test fun `auth errors follow the recipe contract`() = runTest {
        for ((status, expected) in listOf(
            HttpStatusCode.Unauthorized to UnauthorizedException::class.java,
            HttpStatusCode.NotFound to UnauthorizedException::class.java,
            HttpStatusCode.Forbidden to ForbiddenException::class.java,
            HttpStatusCode.ServiceUnavailable to ServiceUnavailableException::class.java,
        )) {
            client { respond("{}", status, headersOf(HttpHeaders.ContentType, "application/json")) }.use {
                assertThrows(expected) { kotlinx.coroutines.runBlocking { AuthServicesUserRepository("http://auth/v1", it).getUserUid("token") } }
            }
        }
    }
    @Test fun `malformed identity is internal error`() = runTest {
        for (body in listOf("{}", "{\"uid\":\"\"}", "{\"uid\":\"a/b\"}", "{\"uid\":\" padded \"}", "not-json")) {
            client { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }.use {
                assertThrows(InternalServiceException::class.java) { kotlinx.coroutines.runBlocking { AuthServicesUserRepository("http://auth/v1", it).getUserUid("token") } }
            }
        }
    }
    @Test fun `transport unavailability is recoverable and cancellation propagates`() = runTest {
        client { throw IOException("unavailable") }.use {
            assertThrows(ServiceUnavailableException::class.java) { kotlinx.coroutines.runBlocking { AuthServicesUserRepository("http://auth/v1", it).getUserUid("token") } }
        }
        client { throw CancellationException("cancelled") }.use {
            assertThrows(CancellationException::class.java) { kotlinx.coroutines.runBlocking { AuthServicesUserRepository("http://auth/v1", it).getUserUid("token") } }
        }
    }
    private fun client(handler: MockRequestHandler) = HttpClient(MockEngine(handler)) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
}
