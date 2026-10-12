package com.elceller.bebilo.recipes.data.repository

import com.elceller.bebilo.recipes.domain.model.*
import com.elceller.bebilo.recipes.domain.repository.AuthUserRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class AuthServicesUserRepository(
    private val baseUrl: String,
    private val client: HttpClient = defaultClient(),
) : AuthUserRepository, AutoCloseable {
    override suspend fun getUserUid(bearerToken: String): String {
        val response = try {
            client.post("${baseUrl.trimEnd('/')}/user-info") {
                contentType(ContentType.Application.Json)
                bearerAuth(bearerToken)
                setBody(UserInfoRequest(bearerToken))
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: IOException) {
            throw ServiceUnavailableException()
        }
        when (response.status) {
            HttpStatusCode.Unauthorized, HttpStatusCode.NotFound -> throw UnauthorizedException()
            HttpStatusCode.Forbidden -> throw ForbiddenException()
            HttpStatusCode.BadRequest -> throw UnauthorizedException()
            else -> if (response.status.value !in 200..299) throw ServiceUnavailableException()
        }
        val uid = try {
            response.body<UserInfoResponse>().uid
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            throw InternalServiceException()
        }
        if (uid.isBlank() || uid != uid.trim() || uid.contains('/') || uid in listOf(".", "..")) {
            throw InternalServiceException()
        }
        return uid
    }
    override fun close() = client.close()

    @Serializable private data class UserInfoRequest(val token: String)
    @Serializable private data class UserInfoResponse(val uid: String)
    companion object {
        private fun defaultClient() = HttpClient(CIO) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(HttpTimeout) { requestTimeoutMillis = 5000; connectTimeoutMillis = 3000 }
        }
    }
}
