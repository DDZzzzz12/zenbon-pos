package org.example.project.auth.data

import kotlinx.serialization.Serializable

object SessionPolicyDto {
    @Serializable
    data class Policy(
        val dailyResetEnabled: Boolean,
        val dailyResetTime: String,
    )

    @Serializable
    data class UpdatePolicyRequest(
        val dailyResetEnabled: Boolean,
        val dailyResetTime: String,
    )
}
