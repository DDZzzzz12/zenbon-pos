package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.AccessLevel.Public
import org.example.project.infrastructure.security.RuleModule

/**
 * Products, categories, extras, choice groups, Pfand types, payment types and VAT rates —
 * `ProductRoutes.kt`, `ProductCategoryRoutes.kt`, `ProductExtrasRoutes.kt`,
 * `ProductExtrasAssignmentRoutes.kt`, `ChoiceGroupRoutes.kt`, `ProductChoiceGroupRoutes.kt`,
 * `PfandTypeRoutes.kt`, `PaymentTypeRoutes.kt`, `VatRoutes.kt`.
 *
 * The menu the till renders. Reads were `Authenticated`, so `CATALOG_READ` sits in
 * `TERMINAL_DEVICE`. `CATALOG_MANAGE` covers the edits **and** the two back-office-only reads
 * (`payment-types/active`, `payment-types/statistics`) that are manager-gated today.
 *
 * The Pfand-type writes now carry `CATALOG_MANAGE` like every other catalog edit. Stage 1 had to
 * give them `SYSTEM_CONFIG` because they were `PermissionOrRole(EXCEPTION_SYSTEM_CONFIG, MANAGER)`
 * and a grant holder had to keep reaching them. Stage 2a retired that mechanism — no employee could
 * ever hold the grant — so the oddity of a catalog route requiring system configuration is gone.
 * Verdict-identical either way: both capabilities sit in `MANAGER`.
 */
object CatalogRules : RuleModule {
    override val name = "Catalog"

    override val pathPrefixes =
        listOf(
            "/api/products",
            "/api/product-categories",
            "/api/product-extras",
            "/api/product-extras-assignment",
            "/api/choice-groups",
            "/api/product-choice-groups",
            "/api/pfand-types",
            "/api/payment-types",
            "/api/vat",
        )

    override val allowedCapabilities =
        setOf(
            Capability.CATALOG_MANAGE,
            Capability.CATALOG_READ,
        )

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/vat" to HttpMethod.Get) to Cap(Capability.CATALOG_READ),
            ("/api/vat" to HttpMethod.Post) to Cap(Capability.CATALOG_MANAGE),
            ("/api/vat/{id}" to HttpMethod.Put) to Cap(Capability.CATALOG_MANAGE),
            ("/api/vat/{id}" to HttpMethod.Delete) to Cap(Capability.CATALOG_MANAGE),
            ("/api/payment-types" to HttpMethod.Get) to Cap(Capability.CATALOG_READ),
            ("/api/payment-types" to HttpMethod.Post) to Cap(Capability.CATALOG_MANAGE),
            ("/api/payment-types/active" to HttpMethod.Get) to Cap(Capability.CATALOG_MANAGE),
            ("/api/payment-types/statistics" to HttpMethod.Get) to Cap(Capability.CATALOG_MANAGE),
            ("/api/payment-types/sync/{lastSyncTime}" to HttpMethod.Get) to Cap(Capability.CATALOG_READ),
            ("/api/payment-types/{id}" to HttpMethod.Get) to Cap(Capability.CATALOG_READ),
            ("/api/payment-types/{id}" to HttpMethod.Put) to Cap(Capability.CATALOG_MANAGE),
            ("/api/payment-types/{id}" to HttpMethod.Delete) to Cap(Capability.CATALOG_MANAGE),
            ("/api/product-categories" to HttpMethod.Get) to Cap(Capability.CATALOG_READ),
            ("/api/product-categories/{id}" to HttpMethod.Get) to Cap(Capability.CATALOG_READ),
            ("/api/product-categories" to HttpMethod.Post) to Cap(Capability.CATALOG_MANAGE),
            ("/api/product-categories/{id}" to HttpMethod.Put) to Cap(Capability.CATALOG_MANAGE),
            ("/api/product-categories/sync/{lastSyncTime}" to HttpMethod.Get) to Cap(Capability.CATALOG_READ),
            ("/api/product-categories/{id}" to HttpMethod.Delete) to Cap(Capability.CATALOG_MANAGE),
            ("/api/product-categories/positions" to HttpMethod.Put) to Cap(Capability.CATALOG_MANAGE),
            ("/api/product-categories/import-csv/{overwrite}" to HttpMethod.Post) to Cap(Capability.CATALOG_MANAGE),
            ("/api/product-extras" to HttpMethod.Get) to Cap(Capability.CATALOG_READ),
            ("/api/product-extras/{id}" to HttpMethod.Get) to Cap(Capability.CATALOG_READ),
            ("/api/product-extras/sync/{lastSyncTime}" to HttpMethod.Get) to Cap(Capability.CATALOG_READ),
            ("/api/product-extras" to HttpMethod.Post) to Cap(Capability.CATALOG_MANAGE),
            ("/api/product-extras/{id}" to HttpMethod.Put) to Cap(Capability.CATALOG_MANAGE),
            ("/api/product-extras/{id}" to HttpMethod.Delete) to Cap(Capability.CATALOG_MANAGE),
            ("/api/product-extras/import-csv/{overwrite}" to HttpMethod.Post) to Cap(Capability.CATALOG_MANAGE),
            ("/api/product-extras-assignment" to HttpMethod.Get) to Cap(Capability.CATALOG_READ),
            ("/api/product-extras-assignment/sync/{lastSyncTime}" to HttpMethod.Get) to Cap(Capability.CATALOG_READ),
            ("/api/product-extras-assignment/product/{productId}" to HttpMethod.Get) to Cap(Capability.CATALOG_READ),
            ("/api/product-extras-assignment" to HttpMethod.Post) to Cap(Capability.CATALOG_MANAGE),
            ("/api/product-extras-assignment/bulk-category" to HttpMethod.Post) to Cap(Capability.CATALOG_MANAGE),
            ("/api/product-extras-assignment/{productId}/{extrasId}" to HttpMethod.Delete) to Cap(Capability.CATALOG_MANAGE),
            ("/api/product-extras-assignment/bulk-category/{categoryId}/{extrasId}" to HttpMethod.Delete) to Cap(Capability.CATALOG_MANAGE),
            ("/api/choice-groups" to HttpMethod.Get) to Cap(Capability.CATALOG_READ),
            ("/api/choice-groups/sync/{lastSyncTime}" to HttpMethod.Get) to Cap(Capability.CATALOG_READ),
            ("/api/choice-groups/{id}" to HttpMethod.Get) to Cap(Capability.CATALOG_READ),
            ("/api/choice-groups" to HttpMethod.Post) to Cap(Capability.CATALOG_MANAGE),
            ("/api/choice-groups/{id}" to HttpMethod.Put) to Cap(Capability.CATALOG_MANAGE),
            ("/api/choice-groups/{id}" to HttpMethod.Delete) to Cap(Capability.CATALOG_MANAGE),
            ("/api/product-choice-groups" to HttpMethod.Get) to Cap(Capability.CATALOG_READ),
            ("/api/product-choice-groups/sync/{lastSyncTime}" to HttpMethod.Get) to Cap(Capability.CATALOG_READ),
            ("/api/product-choice-groups/product/{productId}" to HttpMethod.Get) to Cap(Capability.CATALOG_READ),
            ("/api/product-choice-groups" to HttpMethod.Post) to Cap(Capability.CATALOG_MANAGE),
            ("/api/product-choice-groups/bulk-category" to HttpMethod.Post) to Cap(Capability.CATALOG_MANAGE),
            ("/api/product-choice-groups/bulk-category/{categoryId}/{groupId}" to HttpMethod.Delete) to Cap(Capability.CATALOG_MANAGE),
            ("/api/products" to HttpMethod.Get) to Cap(Capability.CATALOG_READ),
            ("/api/products/search" to HttpMethod.Get) to Cap(Capability.CATALOG_READ),
            ("/api/products/by-category/{categoryId}" to HttpMethod.Get) to Cap(Capability.CATALOG_READ),
            ("/api/products/by-number/{productNumber}" to HttpMethod.Get) to Cap(Capability.CATALOG_READ),
            ("/api/products/{id}" to HttpMethod.Get) to Cap(Capability.CATALOG_READ),
            ("/api/products/sync/{lastSyncTime}" to HttpMethod.Get) to Cap(Capability.CATALOG_READ),
            ("/api/products" to HttpMethod.Post) to Cap(Capability.CATALOG_MANAGE),
            ("/api/products/{id}" to HttpMethod.Put) to Cap(Capability.CATALOG_MANAGE),
            ("/api/products/{id}" to HttpMethod.Delete) to Cap(Capability.CATALOG_MANAGE),
            ("/api/pfand-types" to HttpMethod.Get) to Cap(Capability.CATALOG_READ),
            ("/api/pfand-types" to HttpMethod.Post) to Cap(Capability.CATALOG_MANAGE),
            ("/api/pfand-types/{id}" to HttpMethod.Put) to Cap(Capability.CATALOG_MANAGE),
            ("/api/pfand-types/{id}" to HttpMethod.Delete) to Cap(Capability.CATALOG_MANAGE),
            ("/api/products/import-csv/{overwrite}" to HttpMethod.Post) to Cap(Capability.CATALOG_MANAGE),
            ("/api/products/favourites" to HttpMethod.Get) to Cap(Capability.CATALOG_READ),
            ("/api/products/upload-image" to HttpMethod.Post) to Cap(Capability.CATALOG_MANAGE),
            ("/api/products/images/{filename}" to HttpMethod.Get) to Public,
        )
}
