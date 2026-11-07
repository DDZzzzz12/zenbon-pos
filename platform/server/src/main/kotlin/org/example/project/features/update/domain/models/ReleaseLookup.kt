package org.example.project.features.update.domain.models

/**
 * The outcome of asking GitHub for the latest release.
 *
 * [AuthFailed] exists because an unauthenticated request to a private repository answers **404**,
 * not 403 -- so an expired token is indistinguishable from "this repository has no releases"
 * unless the two are told apart deliberately. Collapsing them would tell an operator whose PAT
 * lapsed that they are up to date, forever.
 */
sealed interface ReleaseLookup {
    data class Found(
        val update: AvailableUpdate,
    ) : ReleaseLookup

    data object UpToDate : ReleaseLookup

    data object AuthFailed : ReleaseLookup

    data class Unreachable(
        val reason: String,
    ) : ReleaseLookup
}
