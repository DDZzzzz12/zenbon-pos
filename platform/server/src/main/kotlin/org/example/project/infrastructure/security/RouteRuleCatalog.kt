package org.example.project.infrastructure.security

import io.ktor.http.HttpMethod

/**
 * Read access to the server's route-permission table.
 *
 * A port: the table itself (`RoutePermissions`) is host wiring that lists every feature's routes, so
 * platform code (permission diagnostics, the admin dump, the log path normalizer) reads it through
 * this interface instead of naming the object. The host binds it in Koin.
 */
interface RouteRuleCatalog {
    /** The rule for [path] and [method], or null when none is registered. */
    fun findRule(
        path: String,
        method: HttpMethod,
    ): AccessLevel?

    /** Every registered rule, typed, so a caller can evaluate it against a capability set. */
    fun registeredRules(): List<Triple<String, HttpMethod, AccessLevel>>

    /** Every rule rendered as strings (path, method, access) for the raw admin dump. */
    fun allRules(): List<Map<String, String>>
}
