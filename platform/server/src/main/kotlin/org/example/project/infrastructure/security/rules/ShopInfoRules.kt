package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.AccessLevel.Public
import org.example.project.infrastructure.security.RuleModule

/**
 * Shop master data and logo — `ShopInfoRoutes.kt`.
 *
 * `GET /api/shop-info/sync` is the only till-facing route (`SHOP_INFO_READ`); every other read
 * and every write is `Role(ADMIN)`, which is exactly the `_MANAGE` shape. The public logo route
 * stays `Public` — receipts and the login screen render it before anyone is authenticated.
 */
object ShopInfoRules : RuleModule {
    override val name = "Shop info"

    override val pathPrefixes = listOf("/api/shop-info")

    override val allowedCapabilities = setOf(Capability.SHOP_INFO_MANAGE, Capability.SHOP_INFO_READ)

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/shop-info" to HttpMethod.Get) to Cap(Capability.SHOP_INFO_MANAGE),
            ("/api/shop-info/{id}" to HttpMethod.Get) to Cap(Capability.SHOP_INFO_MANAGE),
            ("/api/shop-info/sync" to HttpMethod.Get) to Cap(Capability.SHOP_INFO_READ),
            ("/api/shop-info" to HttpMethod.Post) to Cap(Capability.SHOP_INFO_MANAGE),
            ("/api/shop-info/{id}" to HttpMethod.Put) to Cap(Capability.SHOP_INFO_MANAGE),
            ("/api/shop-info/{id}" to HttpMethod.Delete) to Cap(Capability.SHOP_INFO_MANAGE),
            ("/api/shop-info/upload-logo" to HttpMethod.Post) to Cap(Capability.SHOP_INFO_MANAGE),
            ("/api/shop-info/logo/{filename}" to HttpMethod.Get) to Public,
        )
}
