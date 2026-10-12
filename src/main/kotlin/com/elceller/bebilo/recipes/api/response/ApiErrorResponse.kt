package com.elceller.bebilo.recipes.api.response

import kotlinx.serialization.Serializable

@Serializable data class ApiErrorResponse(val code: String, val message: String)
