package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.AccessLevel.Public
import org.example.project.infrastructure.security.RuleModule

/**
 * Software update — `UpdateRoutes.kt`.
 *
 * Only the two reads carry [Capability.UPDATE_READ]. Everything that changes where a release comes
 * from, stores the credential used to fetch it, or installs one, carries [Capability.UPDATE_MANAGE]
 * — including `POST /check` and `POST /stage`, which are reads in HTTP terms but reach out to the
 * network with the stored token and write to disk.
 *
 * The two `client/android` routes are `Public`: a till checks for a new APK on its login screen,
 * before any session exists (precedent: `ShopInfoRules`' public logo route).
 */
object UpdateRules : RuleModule {
    override val name = "Software update"

    override val pathPrefixes = listOf("/api/update")

    override val allowedCapabilities = setOf(Capability.UPDATE_READ, Capability.UPDATE_MANAGE)

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/update/status" to HttpMethod.Get) to Cap(Capability.UPDATE_READ),
            ("/api/update/settings" to HttpMethod.Get) to Cap(Capability.UPDATE_READ),
            ("/api/update/settings" to HttpMethod.Put) to Cap(Capability.UPDATE_MANAGE),
            ("/api/update/token" to HttpMethod.Put) to Cap(Capability.UPDATE_MANAGE),
            ("/api/update/token" to HttpMethod.Delete) to Cap(Capability.UPDATE_MANAGE),
            ("/api/update/check" to HttpMethod.Post) to Cap(Capability.UPDATE_MANAGE),
            ("/api/update/stage" to HttpMethod.Post) to Cap(Capability.UPDATE_MANAGE),
            ("/api/update/apply" to HttpMethod.Post) to Cap(Capability.UPDATE_MANAGE),
            // Public: the till checks on the login screen, before any session exists. Precedent:
            // ShopInfoRules' public logo route. The APK holds no secret (the public repository
            // publishes the same file) and only a digest-verified file is ever served.
            ("/api/update/client/android" to HttpMethod.Get) to Public,
            ("/api/update/client/android/download" to HttpMethod.Get) to Public,
        )
}
