package org.example.project.infrastructure.security

import io.ktor.http.HttpMethod

/** Host binding of [RouteRuleCatalog]: delegates to the [RoutePermissions] table. */
class RoutePermissionsRuleCatalog : RouteRuleCatalog {
    override fun findRule(
        path: String,
        method: HttpMethod,
    ): AccessLevel? = RoutePermissions.findRule(path, method)

    override fun registeredRules(): List<Triple<String, HttpMethod, AccessLevel>> = RoutePermissions.registeredRules()

    override fun allRules(): List<Map<String, String>> = RoutePermissions.allRules()
}
