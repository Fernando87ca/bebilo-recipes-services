package com.elceller.bebilo.recipes.api.response

import kotlinx.serialization.Serializable

@Serializable
data class RecipeStatusEventDto(val id: String, val status: RecipeStatusDto)
