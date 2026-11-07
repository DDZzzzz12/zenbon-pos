package org.example.project.core.error

import kotlinx.serialization.Serializable

@Serializable
data class FieldError(
    val field: String,
    val code: String,
)
