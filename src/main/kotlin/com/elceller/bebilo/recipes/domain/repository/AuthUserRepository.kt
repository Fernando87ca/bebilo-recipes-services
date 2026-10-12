package com.elceller.bebilo.recipes.domain.repository

interface AuthUserRepository {
    suspend fun getUserUid(bearerToken: String): String
}
