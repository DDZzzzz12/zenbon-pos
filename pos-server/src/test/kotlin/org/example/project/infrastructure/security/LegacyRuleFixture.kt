package org.example.project.infrastructure.security

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.infrastructure.security.LegacyAccessLevel.*

/**
 * The pre-migration route-rule map, frozen verbatim as of Stage 1 (504 rules, `dev` @ 1e147015b).
 *
 * **Never edit this by hand, and never let it drift toward the live registry.** It is the baseline
 * [CapabilityEquivalenceTest] compares against, so a "fix" applied here silently blesses whatever
 * the migration broke — which is the one failure mode the whole Stage 1 safety net exists to catch.
 *
 * Test-only. Never shipped.
 */
object LegacyRuleFixture {
    val rules: Map<Pair<String, HttpMethod>, LegacyAccessLevel> =
        mapOf(
            // =====================================================================
            // Auth — AuthRoutes.kt
            // route("/api/auth")
            // =====================================================================
            ("/api/auth/login" to HttpMethod.Post) to Public,
            ("/api/auth/refresh" to HttpMethod.Post) to Public,
            ("/api/auth/validate" to HttpMethod.Get) to Authenticated,
            ("/api/auth/logout" to HttpMethod.Post) to Authenticated,
            ("/api/auth/me" to HttpMethod.Get) to Authenticated,
            // =====================================================================
            // Session Admin — SessionAdminRoutes.kt
            // route("/api/auth/sessions")
            // =====================================================================
            ("/api/auth/sessions/policy" to HttpMethod.Get) to Authenticated,
            ("/api/auth/sessions/policy" to HttpMethod.Put) to Role(EmployeeRole.ADMIN),
            ("/api/auth/sessions/revoke-all" to HttpMethod.Post) to Role(EmployeeRole.ADMIN),
            // =====================================================================
            // Auth Approvals — ApprovalRoutes.kt
            // route("/api/auth/approvals")
            // =====================================================================
            ("/api/auth/approvals/pending" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/auth/approvals/{requestId}/approve" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/auth/approvals/{requestId}/deny" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/auth/approvals/{requestId}/status" to HttpMethod.Get) to Authenticated,
            ("/api/auth/approvals/{requestId}" to HttpMethod.Delete) to Role(EmployeeRole.MANAGER),
            // =====================================================================
            // Temporary Sessions — TemporarySessionRoutes.kt
            // route("/api/auth/temporary-session")
            // =====================================================================
            ("/api/auth/temporary-session" to HttpMethod.Post) to Authenticated,
            ("/api/auth/temporary-session/active" to HttpMethod.Get) to Authenticated,
            // =====================================================================
            // Initial Setup — InitialSetupRoutes.kt
            // route("/api/setup") — no auth
            // =====================================================================
            ("/api/setup/status" to HttpMethod.Get) to Public,
            // NOT Public, unlike its neighbours: reports fiscal terminal + TSE commissioning state.
            ("/api/setup/readiness" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/setup/initialize" to HttpMethod.Post) to Public,
            ("/api/setup/restore-backup" to HttpMethod.Post) to Public,
            // Client-reported errors: deliberately Public here because the report is gated on a
            // paired device token inside the route, not a JWT — the errors most worth capturing
            // (login failure, token refresh) happen when no valid JWT exists by definition.
            ("/api/v1/client-errors" to HttpMethod.Post) to Public,
            // =====================================================================
            // Shop Info — ShopInfoRoutes.kt
            // route("/api/shop-info")
            // =====================================================================
            ("/api/shop-info" to HttpMethod.Get) to Role(EmployeeRole.ADMIN),
            ("/api/shop-info/{id}" to HttpMethod.Get) to Role(EmployeeRole.ADMIN),
            ("/api/shop-info/sync" to HttpMethod.Get) to Authenticated,
            ("/api/shop-info" to HttpMethod.Post) to Role(EmployeeRole.ADMIN),
            ("/api/shop-info/{id}" to HttpMethod.Put) to Role(EmployeeRole.ADMIN),
            ("/api/shop-info/{id}" to HttpMethod.Delete) to Role(EmployeeRole.ADMIN),
            ("/api/shop-info/upload-logo" to HttpMethod.Post) to Role(EmployeeRole.ADMIN),
            // Public (outside authenticate block)
            ("/api/shop-info/logo/{filename}" to HttpMethod.Get) to Public,
            // =====================================================================
            // Configuration — ConfigurationRoutes.kt
            // route("/api/v1/config")
            // =====================================================================
            ("/api/v1/config/{shopId}/vat-mapping" to HttpMethod.Get) to Authenticated,
            ("/api/v1/config/{shopId}/vat-mapping" to HttpMethod.Put) to
                PermissionOrRole(LegacyPermission.EXCEPTION_SYSTEM_CONFIG, EmployeeRole.MANAGER),
            ("/api/v1/config/{shopId}/voucher-config" to HttpMethod.Get) to Authenticated,
            ("/api/v1/config/{shopId}/voucher-config" to HttpMethod.Put) to
                PermissionOrRole(LegacyPermission.EXCEPTION_SYSTEM_CONFIG, EmployeeRole.MANAGER),
            ("/api/v1/config/{shopId}/print-settings" to HttpMethod.Get) to Authenticated,
            ("/api/v1/config/{shopId}/print-settings" to HttpMethod.Put) to
                PermissionOrRole(LegacyPermission.EXCEPTION_SYSTEM_CONFIG, EmployeeRole.MANAGER),
            ("/api/v1/config/{shopId}/datev-xml" to HttpMethod.Get) to Authenticated,
            ("/api/v1/config/{shopId}/datev-xml" to HttpMethod.Put) to
                PermissionOrRole(LegacyPermission.EXCEPTION_SYSTEM_CONFIG, EmployeeRole.MANAGER),
            // POS-ERP cash-ledger integration. ADMIN on both sides, stricter than the neighbouring
            // config keys: the PUT accepts a credential, and even the GET reveals whether one is set
            // and where transits are announced to.
            ("/api/v1/config/{shopId}/erp-integration" to HttpMethod.Get) to Role(EmployeeRole.ADMIN),
            ("/api/v1/config/{shopId}/erp-integration" to HttpMethod.Put) to Role(EmployeeRole.ADMIN),
            ("/api/v1/config/{shopId}/order-rules" to HttpMethod.Get) to Authenticated,
            ("/api/v1/config/{shopId}/order-rules" to HttpMethod.Put) to
                PermissionOrRole(LegacyPermission.EXCEPTION_SYSTEM_CONFIG, EmployeeRole.MANAGER),
            ("/api/v1/config/{shopId}/billing-rules" to HttpMethod.Get) to Authenticated,
            ("/api/v1/config/{shopId}/billing-rules" to HttpMethod.Put) to
                PermissionOrRole(LegacyPermission.EXCEPTION_SYSTEM_CONFIG, EmployeeRole.MANAGER),
            ("/api/v1/config/{shopId}/browser-stations" to HttpMethod.Get) to Authenticated,
            ("/api/v1/config/{shopId}/browser-stations" to HttpMethod.Put) to
                PermissionOrRole(LegacyPermission.EXCEPTION_SYSTEM_CONFIG, EmployeeRole.MANAGER),
            ("/api/v1/config/{shopId}/break-rules" to HttpMethod.Get) to Authenticated,
            ("/api/v1/config/{shopId}/break-rules" to HttpMethod.Put) to
                PermissionOrRole(LegacyPermission.EXCEPTION_SYSTEM_CONFIG, EmployeeRole.MANAGER),
            ("/api/v1/config/{shopId}/document-storage" to HttpMethod.Get) to Authenticated,
            ("/api/v1/config/{shopId}/document-storage" to HttpMethod.Put) to
                PermissionOrRole(LegacyPermission.EXCEPTION_SYSTEM_CONFIG, EmployeeRole.MANAGER),
            ("/api/v1/config/{shopId}/datev-account-mapping" to HttpMethod.Get) to Authenticated,
            ("/api/v1/config/{shopId}/datev-account-mapping" to HttpMethod.Put) to
                PermissionOrRole(LegacyPermission.EXCEPTION_SYSTEM_CONFIG, EmployeeRole.MANAGER),
            // Which cash book each Z-Bon moves the day's takings into (the automatic Geldtransit).
            // Graded like every other /api/v1/config sub-resource: read for any authenticated
            // employee, write for system-config permission or manager — the same bar as the
            // manager-only cash-book create/update this picker is edited beside.
            ("/api/v1/config/{shopId}/cash-book-transfer-target" to HttpMethod.Get) to Authenticated,
            ("/api/v1/config/{shopId}/cash-book-transfer-target" to HttpMethod.Put) to
                PermissionOrRole(LegacyPermission.EXCEPTION_SYSTEM_CONFIG, EmployeeRole.MANAGER),
            // How far back a Kassenbuch Beleg date may lie. Same grading as the other config
            // sub-resources; the till reads it to bound its entry form's date picker.
            ("/api/v1/config/{shopId}/kassenbuch-beleg-age" to HttpMethod.Get) to Authenticated,
            ("/api/v1/config/{shopId}/kassenbuch-beleg-age" to HttpMethod.Put) to
                PermissionOrRole(LegacyPermission.EXCEPTION_SYSTEM_CONFIG, EmployeeRole.MANAGER),
            ("/api/v1/config/{shopId}/kassen-meldung" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/v1/config/{shopId}/kassen-meldung/pdf" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            // =====================================================================
            // Entry Logs — EntryLogRoutes.kt
            // route("/api/entry-logs")
            // =====================================================================
            ("/api/entry-logs/paginated" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            // =====================================================================
            // Change Feed — ChangeFeedRoutes.kt
            // route("/api/changes")
            // =====================================================================
            ("/api/changes" to HttpMethod.Get) to Authenticated,
            // =====================================================================
            // Transaction Audit — TransactionAuditRoutes.kt
            // route("/api/audit/transactions")
            // =====================================================================
            ("/api/audit/transactions" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/audit/transactions/paginated" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            // =====================================================================
            // VAT — VatRoutes.kt
            // route("/api/vat")
            // =====================================================================
            ("/api/vat" to HttpMethod.Get) to Authenticated,
            ("/api/vat" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/vat/{id}" to HttpMethod.Put) to Role(EmployeeRole.MANAGER),
            ("/api/vat/{id}" to HttpMethod.Delete) to Role(EmployeeRole.MANAGER),
            // =====================================================================
            // Orders — OrderRoutes.kt
            // route("/api/orders")
            // =====================================================================
            ("/api/orders/by-table-session/{tableSessionId}" to HttpMethod.Get) to Authenticated,
            ("/api/orders" to HttpMethod.Post) to Authenticated,
            ("/api/orders/reference-print" to HttpMethod.Post) to Authenticated,
            // =====================================================================
            // Order Items — OrderItemRoutes.kt
            // route("/api/items")
            // =====================================================================
            ("/api/items/session/{sessionId}" to HttpMethod.Get) to Authenticated,
            ("/api/items/cancellations" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/items/cancel/{sourceTableId}" to HttpMethod.Post) to Custom("hybrid auth - cancel order items"),
            ("/api/items/transfer/{currentTableId}" to HttpMethod.Post) to Custom("hybrid auth - transfer items"),
            ("/api/items/transfers/{tableSessionId}" to HttpMethod.Get) to Authenticated,
            ("/api/items/transfers" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            // =====================================================================
            // Payment Types — PaymentTypeRoutes.kt
            // route("/api/payment-types")
            // =====================================================================
            ("/api/payment-types" to HttpMethod.Get) to Authenticated,
            ("/api/payment-types" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/payment-types/active" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/payment-types/statistics" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/payment-types/sync/{lastSyncTime}" to HttpMethod.Get) to Authenticated,
            ("/api/payment-types/{id}" to HttpMethod.Get) to Authenticated,
            ("/api/payment-types/{id}" to HttpMethod.Put) to Role(EmployeeRole.MANAGER),
            ("/api/payment-types/{id}" to HttpMethod.Delete) to Role(EmployeeRole.MANAGER),
            // =====================================================================
            // Employees — EmployeeRoutes.kt
            // route("/api/employees")
            // =====================================================================
            ("/api/employees/me" to HttpMethod.Get) to Authenticated,
            ("/api/employees/info/active" to HttpMethod.Get) to Authenticated,
            ("/api/employees/summary" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/employees" to HttpMethod.Post) to AnyRole(setOf(EmployeeRole.MANAGER, EmployeeRole.ADMIN)),
            ("/api/employees/{id}" to HttpMethod.Get) to Custom("self or manager"),
            ("/api/employees/{id}" to HttpMethod.Put) to Custom("self or manager"),
            ("/api/employees/{id}" to HttpMethod.Delete) to Role(EmployeeRole.MANAGER),
            ("/api/employees/{id}/deactivate" to HttpMethod.Patch) to Role(EmployeeRole.MANAGER),
            ("/api/employees/{id}/rotation-pool" to HttpMethod.Patch) to AnyRole(setOf(EmployeeRole.MANAGER, EmployeeRole.ADMIN)),
            ("/api/employees/{id}/password" to HttpMethod.Patch) to Custom("self or manager"),
            ("/api/employees/language" to HttpMethod.Patch) to Authenticated,
            ("/api/employees/me/pin" to HttpMethod.Patch) to Authenticated,
            ("/api/employees/authenticate" to HttpMethod.Post) to Authenticated,
            ("/api/employees/{id}/reset-password" to HttpMethod.Post) to AnyRole(setOf(EmployeeRole.MANAGER, EmployeeRole.ADMIN)),
            ("/api/employees/{id}/reset-pin" to HttpMethod.Post) to AnyRole(setOf(EmployeeRole.MANAGER, EmployeeRole.ADMIN)),
            ("/api/employees/{employeeId}/schedule" to HttpMethod.Get) to Custom("self or manager"),
            ("/api/employees/{employeeId}/schedule" to HttpMethod.Put) to Role(EmployeeRole.MANAGER),
            ("/api/employees/{employeeId}/schedule/day" to HttpMethod.Put) to Role(EmployeeRole.MANAGER),
            // =====================================================================
            // Employee Info — EmployeeInfoRoutes.kt
            // route("api/employee/info") — NOTE: no leading slash in route file
            // =====================================================================
            ("/api/employee/info/me" to HttpMethod.Get) to Authenticated,
            ("/api/employee/info/me" to HttpMethod.Put) to Authenticated,
            ("/api/employee/info" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/employee/info/by-employee/{employeeId}" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/employee/info/by-employee/{employeeId}" to HttpMethod.Put) to Role(EmployeeRole.MANAGER),
            ("/api/employee/info/by-employee/{employeeId}" to HttpMethod.Delete) to Role(EmployeeRole.MANAGER),
            ("/api/employee/info" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            // =====================================================================
            // Product Categories — ProductCategoryRoutes.kt
            // route("/api/product-categories")
            // =====================================================================
            ("/api/product-categories" to HttpMethod.Get) to Authenticated,
            ("/api/product-categories/{id}" to HttpMethod.Get) to Authenticated,
            ("/api/product-categories" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/product-categories/{id}" to HttpMethod.Put) to Role(EmployeeRole.MANAGER),
            ("/api/product-categories/sync/{lastSyncTime}" to HttpMethod.Get) to Authenticated,
            ("/api/product-categories/{id}" to HttpMethod.Delete) to Role(EmployeeRole.MANAGER),
            ("/api/product-categories/positions" to HttpMethod.Put) to Role(EmployeeRole.MANAGER),
            ("/api/product-categories/import-csv/{overwrite}" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            // =====================================================================
            // Product Extras — ProductExtrasRoutes.kt
            // route("/api/product-extras")
            // =====================================================================
            ("/api/product-extras" to HttpMethod.Get) to Authenticated,
            ("/api/product-extras/{id}" to HttpMethod.Get) to Authenticated,
            ("/api/product-extras/sync/{lastSyncTime}" to HttpMethod.Get) to Authenticated,
            ("/api/product-extras" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/product-extras/{id}" to HttpMethod.Put) to Role(EmployeeRole.MANAGER),
            ("/api/product-extras/{id}" to HttpMethod.Delete) to Role(EmployeeRole.MANAGER),
            ("/api/product-extras/import-csv/{overwrite}" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            // =====================================================================
            // Product Extras Assignment — ProductExtrasAssignmentRoutes.kt
            // route("/api/product-extras-assignment")
            // =====================================================================
            ("/api/product-extras-assignment" to HttpMethod.Get) to Authenticated,
            ("/api/product-extras-assignment/sync/{lastSyncTime}" to HttpMethod.Get) to Authenticated,
            ("/api/product-extras-assignment/product/{productId}" to HttpMethod.Get) to Authenticated,
            ("/api/product-extras-assignment" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/product-extras-assignment/bulk-category" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/product-extras-assignment/{productId}/{extrasId}" to HttpMethod.Delete) to Role(EmployeeRole.MANAGER),
            ("/api/product-extras-assignment/bulk-category/{categoryId}/{extrasId}" to HttpMethod.Delete) to Role(EmployeeRole.MANAGER),
            // Choice Groups — ChoiceGroupRoutes.kt
            ("/api/choice-groups" to HttpMethod.Get) to Authenticated,
            ("/api/choice-groups/sync/{lastSyncTime}" to HttpMethod.Get) to Authenticated,
            ("/api/choice-groups/{id}" to HttpMethod.Get) to Authenticated,
            ("/api/choice-groups" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/choice-groups/{id}" to HttpMethod.Put) to Role(EmployeeRole.MANAGER),
            ("/api/choice-groups/{id}" to HttpMethod.Delete) to Role(EmployeeRole.MANAGER),
            // Product Choice Groups — ProductChoiceGroupRoutes.kt
            ("/api/product-choice-groups" to HttpMethod.Get) to Authenticated,
            ("/api/product-choice-groups/sync/{lastSyncTime}" to HttpMethod.Get) to Authenticated,
            ("/api/product-choice-groups/product/{productId}" to HttpMethod.Get) to Authenticated,
            ("/api/product-choice-groups" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/product-choice-groups/bulk-category" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/product-choice-groups/bulk-category/{categoryId}/{groupId}" to HttpMethod.Delete) to Role(EmployeeRole.MANAGER),
            // =====================================================================
            // Products — ProductRoutes.kt
            // route("/api/products")
            // =====================================================================
            ("/api/products" to HttpMethod.Get) to Authenticated,
            ("/api/products/search" to HttpMethod.Get) to Authenticated,
            ("/api/products/by-category/{categoryId}" to HttpMethod.Get) to Authenticated,
            ("/api/products/by-number/{productNumber}" to HttpMethod.Get) to Authenticated,
            ("/api/products/{id}" to HttpMethod.Get) to Authenticated,
            ("/api/products/sync/{lastSyncTime}" to HttpMethod.Get) to Authenticated,
            ("/api/products" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/products/{id}" to HttpMethod.Put) to Role(EmployeeRole.MANAGER),
            ("/api/products/{id}" to HttpMethod.Delete) to Role(EmployeeRole.MANAGER),
            // Pfand (deposit) type catalog — PfandTypeRoutes.kt
            ("/api/pfand-types" to HttpMethod.Get) to Authenticated,
            ("/api/pfand-types" to HttpMethod.Post) to PermissionOrRole(LegacyPermission.EXCEPTION_SYSTEM_CONFIG, EmployeeRole.MANAGER),
            ("/api/pfand-types/{id}" to HttpMethod.Put) to PermissionOrRole(LegacyPermission.EXCEPTION_SYSTEM_CONFIG, EmployeeRole.MANAGER),
            ("/api/pfand-types/{id}" to HttpMethod.Delete) to
                PermissionOrRole(
                    LegacyPermission.EXCEPTION_SYSTEM_CONFIG,
                    EmployeeRole.MANAGER,
                ),
            ("/api/products/import-csv/{overwrite}" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/products/favourites" to HttpMethod.Get) to Authenticated,
            ("/api/products/upload-image" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            // Public (outside authenticate block)
            ("/api/products/images/{filename}" to HttpMethod.Get) to Public,
            // =====================================================================
            // Tables — RTableRoutes.kt
            // route("/api/tables")
            // =====================================================================
            ("/api/tables/{id}/currentSession" to HttpMethod.Get) to Authenticated,
            ("/api/tables/search" to HttpMethod.Get) to Authenticated,
            ("/api/tables" to HttpMethod.Get) to Authenticated,
            ("/api/tables" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/tables/{id}" to HttpMethod.Get) to Authenticated,
            ("/api/tables/{id}" to HttpMethod.Put) to Role(EmployeeRole.MANAGER),
            ("/api/tables/batch-update-positions" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/tables/{id}" to HttpMethod.Delete) to Role(EmployeeRole.MANAGER),
            ("/api/tables/{id}/transfer" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/tables/{id}/session" to HttpMethod.Delete) to Role(EmployeeRole.MANAGER),
            ("/api/tables/sessions/{sessionId}/customer" to HttpMethod.Get) to Authenticated,
            ("/api/tables/sessions/{sessionId}/assign-customer" to HttpMethod.Post) to Authenticated,
            ("/api/tables/sessions/{sessionId}/remove-customer" to HttpMethod.Delete) to Authenticated,
            ("/api/tables/sessions" to HttpMethod.Get) to Authenticated,
            ("/api/tables/sessions/{id}" to HttpMethod.Get) to Authenticated,
            ("/api/tables/import-csv/{overwrite}" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/tables/{id}/request-billing" to HttpMethod.Post) to Authenticated,
            ("/api/tables/billing-requests/pending" to HttpMethod.Get) to Authenticated,
            ("/api/tables/sync/{lastSyncTime}" to HttpMethod.Get) to Authenticated,
            // Admin-only, idempotent data repair for tables referencing a completed session.
            ("/api/tables/repair-completed-sessions" to HttpMethod.Post) to Role(EmployeeRole.ADMIN),
            // =====================================================================
            // Room Elements — RoomElementRoutes.kt
            // route("/api/room-elements")
            // =====================================================================
            ("/api/room-elements" to HttpMethod.Get) to Authenticated,
            ("/api/room-elements" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/room-elements/{id}" to HttpMethod.Get) to Authenticated,
            ("/api/room-elements/{id}" to HttpMethod.Put) to Role(EmployeeRole.MANAGER),
            ("/api/room-elements/{id}" to HttpMethod.Delete) to Role(EmployeeRole.MANAGER),
            ("/api/room-elements/{id}/position" to HttpMethod.Patch) to Role(EmployeeRole.MANAGER),
            ("/api/room-elements/{id}/dimensions" to HttpMethod.Patch) to Role(EmployeeRole.MANAGER),
            // Read-only floor-plan layout lookups — any authenticated staff needs these to render
            // the floor plan (writes below stay MANAGER-gated), matching the Authenticated base reads above.
            ("/api/room-elements/by-type/{type}" to HttpMethod.Get) to Authenticated,
            ("/api/room-elements/by-location/{locationTypeId}" to HttpMethod.Get) to Authenticated,
            ("/api/room-elements/sync/{lastSyncTime}" to HttpMethod.Get) to Authenticated,
            // =====================================================================
            // Customers — CustomerRoutes.kt
            // route("/api/customers")
            // =====================================================================
            ("/api/customers" to HttpMethod.Get) to Authenticated,
            ("/api/customers/search" to HttpMethod.Get) to Authenticated,
            ("/api/customers/{id}" to HttpMethod.Get) to Authenticated,
            ("/api/customers" to HttpMethod.Post) to Authenticated,
            // =====================================================================
            // Customer Addresses — CustomerAddressRoutes.kt
            // route("/api/customer-addresses")
            // =====================================================================
            ("/api/customer-addresses" to HttpMethod.Get) to Authenticated,
            ("/api/customer-addresses/customer/{customerId}" to HttpMethod.Get) to Authenticated,
            ("/api/customer-addresses" to HttpMethod.Post) to Authenticated,
            // =====================================================================
            // Vouchers — VoucherRoutes.kt
            // route("/api/vouchers")
            // =====================================================================
            ("/api/vouchers/statistics" to HttpMethod.Get) to Authenticated,
            ("/api/vouchers/created-by-date" to HttpMethod.Get) to Authenticated,
            ("/api/vouchers/redeemed-by-date" to HttpMethod.Get) to Authenticated,
            ("/api/vouchers/number/{voucherNumber}/redemption-history" to HttpMethod.Get) to Authenticated,
            ("/api/vouchers" to HttpMethod.Post) to Authenticated,
            ("/api/vouchers/validate" to HttpMethod.Post) to Authenticated,
            // =====================================================================
            // Promotions — PromotionRoutes.kt
            // route("/api/promotions")
            // =====================================================================
            ("/api/promotions" to HttpMethod.Get) to Authenticated,
            ("/api/promotions/all" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/promotions/{id}" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/promotions" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/promotions/{id}" to HttpMethod.Put) to Role(EmployeeRole.MANAGER),
            ("/api/promotions/{id}" to HttpMethod.Delete) to Role(EmployeeRole.MANAGER),
            ("/api/promotions/{id}/usage" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            // =====================================================================
            // Card Terminals — CardTerminalRoutes.kt
            // =====================================================================
            ("/api/card-terminals" to HttpMethod.Get) to Authenticated,
            ("/api/card-terminals/available" to HttpMethod.Get) to Authenticated,
            ("/api/card-terminals/{id}" to HttpMethod.Get) to Authenticated,
            ("/api/card-terminals" to HttpMethod.Post) to Role(EmployeeRole.ADMIN),
            ("/api/card-terminals/{id}" to HttpMethod.Put) to Role(EmployeeRole.ADMIN),
            ("/api/card-terminals/{id}" to HttpMethod.Delete) to Role(EmployeeRole.ADMIN),
            ("/api/card-terminals/{id}/connect" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/card-terminals/{id}/disconnect" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/card-terminals/{id}/status" to HttpMethod.Post) to Authenticated,
            ("/api/card-terminals/{id}/diagnosis" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/card-terminals/{id}/totals-preview" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/card-settlements" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/card-settlements/{id}/reconciliation" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/bills/create-with-card" to HttpMethod.Get) to AnyRole(setOf(EmployeeRole.MANAGER, EmployeeRole.CASHIER)),
            // =====================================================================
            // Notifications — NotificationStreamRoutes.kt
            // SSE real-time notification stream (any authenticated employee)
            // =====================================================================
            ("/api/notifications/stream" to HttpMethod.Get) to Authenticated,
            // =====================================================================
            // Bills — BillRoutes.kt
            // route("/api/v1/bills")
            // =====================================================================
            ("/api/v1/bills" to HttpMethod.Get) to Authenticated,
            ("/api/v1/bills/{id}" to HttpMethod.Get) to Authenticated,
            ("/api/v1/bills/number/{billNumber}" to HttpMethod.Get) to Authenticated,
            ("/api/v1/bills/tse-transaction/{transactionNumber}" to HttpMethod.Get) to Authenticated,
            ("/api/v1/bills/table-session/{tableSessionId}" to HttpMethod.Get) to Authenticated,
            ("/api/v1/bills/search" to HttpMethod.Get) to Authenticated,
            ("/api/v1/bills/summaries" to HttpMethod.Get) to Authenticated,
            ("/api/v1/bills/statistics" to HttpMethod.Get) to Authenticated,
            ("/api/v1/bills/{billId}/print" to HttpMethod.Post) to
                AnyRole(setOf(EmployeeRole.ADMIN, EmployeeRole.MANAGER, EmployeeRole.CASHIER)),
            ("/api/v1/bills" to HttpMethod.Post) to AnyRole(setOf(EmployeeRole.ADMIN, EmployeeRole.MANAGER, EmployeeRole.CASHIER)),
            // Storno (Belegstorno) is a TSE-signed, DSFinV-K-exported reversal — manager+ only.
            ("/api/v1/bills/{id}" to HttpMethod.Delete) to AnyRole(setOf(EmployeeRole.ADMIN, EmployeeRole.MANAGER)),
            // Zahlartwechsel stays open to CASHIER by design: cashiers correct mis-keyed payment types
            // at the register. Do NOT tighten this to manager+ for symmetry with storno above.
            ("/api/v1/bills/{id}/rebook" to HttpMethod.Post) to
                AnyRole(setOf(EmployeeRole.ADMIN, EmployeeRole.MANAGER, EmployeeRole.CASHIER)),
            // =====================================================================
            // Z-Bon Reports — ZBonRoutes.kt
            // route("/api/z-bon")
            // =====================================================================
            ("/api/z-bon" to HttpMethod.Get) to Authenticated,
            // Exact rule, so it resolves ahead of the parameterized "{id}" rule below —
            // findRule() tries exactRules first. Both are MANAGER today; registering "recent"
            // explicitly stops a future change to "{id}" from silently regrading it.
            ("/api/z-bon/recent" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/z-bon/{id}" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/z-bon/history" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/z-bon/latest/datetime" to HttpMethod.Get) to Authenticated,
            ("/api/z-bon" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/z-bon/{id}/pdf" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/z-bon/{id}/print" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            // =====================================================================
            // Statistics — StatisticsRoutes.kt
            // route("/api/statistics")
            // =====================================================================
            ("/api/statistics/sales/overview" to HttpMethod.Get) to Authenticated,
            ("/api/statistics/sales/trends" to HttpMethod.Get) to Authenticated,
            ("/api/statistics/sales/bills" to HttpMethod.Get) to Authenticated,
            ("/api/statistics/sales/by-table" to HttpMethod.Get) to Authenticated,
            ("/api/statistics/sales/by-table/{tableId}/sessions" to HttpMethod.Get) to Authenticated,
            ("/api/statistics/sales/by-employee" to HttpMethod.Get) to Authenticated,
            ("/api/statistics/sales/payments" to HttpMethod.Get) to Authenticated,
            ("/api/statistics/sales/canceled-items" to HttpMethod.Get) to Authenticated,
            ("/api/statistics/sales/sessions" to HttpMethod.Get) to Authenticated,
            ("/api/statistics/financial" to HttpMethod.Get) to Authenticated,
            ("/api/statistics/catalog" to HttpMethod.Get) to Authenticated,
            ("/api/statistics/promotions" to HttpMethod.Get) to Authenticated,
            // =====================================================================
            // Training Report — TrainingReportRoutes.kt
            // route("/api/v1/reports")
            // =====================================================================
            ("/api/v1/reports/training" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            // =====================================================================
            // Kassenbuch — KassenbuchRoutes.kt
            // route("/api/v1/sales-invoices") — issuing a §14 invoice is a manager act, and the
            // archived PDF is the document an auditor is shown, so reading it is gated the same way.
            ("/api/v1/sales-invoices" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/v1/sales-invoices/from-bill" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/v1/sales-invoices/standalone" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/v1/sales-invoices/{id}" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/v1/sales-invoices/{id}" to HttpMethod.Put) to Role(EmployeeRole.MANAGER),
            ("/api/v1/sales-invoices/{id}" to HttpMethod.Delete) to Role(EmployeeRole.MANAGER),
            ("/api/v1/sales-invoices/{id}/issue" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/v1/sales-invoices/{id}/settle-at-till" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/v1/sales-invoices/{id}/cancel" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/v1/sales-invoices/{id}/pdf" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            // The DATEV AR export is a manual ADMIN download, gated one step above the MANAGER
            // routes above it.
            ("/api/v1/sales-invoices/export/datev" to HttpMethod.Get) to Role(EmployeeRole.ADMIN),
            ("/api/v1/sales-invoices/export/datev/statistics" to HttpMethod.Get) to Role(EmployeeRole.ADMIN),
            // route("/api/v1/kassenbuch")
            // =====================================================================
            ("/api/v1/kassenbuch" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/v1/kassenbuch/balance" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/v1/kassenbuch/cash-count" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/v1/kassenbuch/entries" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            // Exact rules, so they are resolved before the parameterized "{entryId}" rule below —
            // findRule() tries exactRules first. All three are MANAGER today, but registering
            // "search" and "recent" explicitly stops a future change to "{entryId}" from
            // silently regrading them.
            ("/api/v1/kassenbuch/entries/search" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/v1/kassenbuch/entries/recent" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/v1/kassenbuch/entries/{entryId}" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/v1/kassenbuch/entries/grouped/{headerId}" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/v1/kassenbuch/export/datev-xml" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/v1/kassenbuch/export/buchungsstapel" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/v1/kassenbuch/entries/{headerId}/link-document" to HttpMethod.Put) to Role(EmployeeRole.MANAGER),
            ("/api/v1/kassenbuch/entries/{headerId}/link-document" to HttpMethod.Delete) to Role(EmployeeRole.MANAGER),
            ("/api/v1/kassenbuch/entries/{headerId}/replace-document" to HttpMethod.Put) to Role(EmployeeRole.MANAGER),
            ("/api/v1/kassenbuch/entries/{headerId}/correct" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/v1/kassenbuch/export/datev-xml/statistics" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            // Cash-book-to-cash-book transfers (Geldtransit) — CashBookRoutes.kt
            // route("/api/v1/kassenbuch/transfers")
            ("/api/v1/kassenbuch/transfers" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/v1/kassenbuch/transfers/{transferId}/reverse" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            // Cash-book CRUD — CashBookRoutes.kt
            // route("/api/v1/kassenbuch/books")
            ("/api/v1/kassenbuch/books" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/v1/kassenbuch/books" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/v1/kassenbuch/books/{bookId}" to HttpMethod.Put) to Role(EmployeeRole.MANAGER),
            // Kassensturz close of a non-fiscal cash book — CashBookRoutes.kt
            // route("/api/v1/kassenbuch/books/{bookId}")
            ("/api/v1/kassenbuch/books/{bookId}/close/preview" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/v1/kassenbuch/books/{bookId}/close" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/v1/kassenbuch/books/{bookId}/closes" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            // POS-ERP transit advice outbox — TransitOutboxRoutes.kt
            ("/api/v1/kassenbuch/transit-outbox" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            // ADMIN, not MANAGER: abandoning closes out records of real cash movement that POS-ERP
            // was never told about, and deliberately leaves the Geldtransit clearing account
            // unbalanced.
            ("/api/v1/kassenbuch/transit-outbox/abandon" to HttpMethod.Post) to Role(EmployeeRole.ADMIN),
            // =====================================================================
            // DATEV Export Log — DatevExportLogRoutes.kt
            // route("/api/v1/datev/export-log")
            // =====================================================================
            ("/api/v1/datev/export-log" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/v1/datev/export-log/{id}/download" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            // =====================================================================
            // DATEV Cadence Export — DatevExportRoutes.kt
            // route("/api/v1/datev/export")
            // =====================================================================
            ("/api/v1/datev/export/catchup/preview" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/v1/datev/export/catchup" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/v1/datev/export/ad-hoc" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            // =====================================================================
            // Monthly Close — MonthlyCloseRoutes.kt
            // route("/api/v1/monthly-close")
            // =====================================================================
            ("/api/v1/monthly-close" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            // =====================================================================
            // DSFinV-K Export — DsfinvkExportRoutes.kt
            // route("/api/v1/dsfinvk")
            // =====================================================================
            ("/api/v1/dsfinvk/export" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/v1/dsfinvk/validate" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/v1/dsfinvk/statistics" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            // =====================================================================
            // Document Management — DocumentRoutes.kt
            // route("/api/documents")
            // =====================================================================
            ("/api/documents/document-types" to HttpMethod.Get) to Authenticated,
            ("/api/documents/upload" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/documents/{id}" to HttpMethod.Get) to Authenticated,
            ("/api/documents/{id}/file" to HttpMethod.Get) to Authenticated,
            ("/api/documents" to HttpMethod.Get) to Authenticated,
            ("/api/documents/{id}" to HttpMethod.Delete) to Role(EmployeeRole.MANAGER),
            ("/api/documents/statistics" to HttpMethod.Get) to Authenticated,
            ("/api/documents/{id}/metadata" to HttpMethod.Put) to Role(EmployeeRole.MANAGER),
            ("/api/documents/{id}/metadata" to HttpMethod.Get) to Authenticated,
            ("/api/documents/link" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/documents/{id}/linked" to HttpMethod.Get) to Authenticated,
            ("/api/documents/link/{linkId}" to HttpMethod.Delete) to Role(EmployeeRole.MANAGER),
            ("/api/documents/unlinked" to HttpMethod.Get) to Authenticated,
            ("/api/documents/{id}/bank-statement-metadata" to HttpMethod.Put) to Role(EmployeeRole.MANAGER),
            ("/api/documents/export/datev-ap" to HttpMethod.Get) to Authenticated,
            ("/api/documents/export/datev-ap/statistics" to HttpMethod.Get) to Authenticated,
            // Normalized invoice aggregate — DocumentRoutes.kt (document-keyed)
            ("/api/documents/{id}/invoice" to HttpMethod.Get) to Authenticated,
            ("/api/documents/{id}/invoice" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/documents/{id}/status" to HttpMethod.Put) to Role(EmployeeRole.MANAGER),
            // =====================================================================
            // Normalized invoice aggregate — InvoiceRoutes.kt
            // route("/api/invoices")
            // =====================================================================
            ("/api/invoices" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/invoices/{id}" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/invoices/{id}" to HttpMethod.Put) to Role(EmployeeRole.MANAGER),
            ("/api/invoices/{id}/booking" to HttpMethod.Put) to Role(EmployeeRole.MANAGER),
            ("/api/invoices/{id}/review" to HttpMethod.Put) to Role(EmployeeRole.MANAGER),
            // =====================================================================
            // Creditor master data — SupplierRoutes.kt
            // route("/api/suppliers")
            // =====================================================================
            ("/api/suppliers" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/suppliers/{id}" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/suppliers" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/suppliers/{id}" to HttpMethod.Put) to Role(EmployeeRole.MANAGER),
            // route("/api/debtors") — the customers we invoice; master data behind a §14 document
            ("/api/debtors" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/debtors/{id}" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/debtors" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/debtors/{id}" to HttpMethod.Put) to Role(EmployeeRole.MANAGER),
            ("/api/debtors/{id}" to HttpMethod.Delete) to Role(EmployeeRole.MANAGER),
            // =====================================================================
            // Recurring obligations — ContractRoutes.kt
            // route("/api/contracts") — no DELETE, deactivation rides on PUT
            // =====================================================================
            ("/api/contracts" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/contracts/alerts" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/contracts/{id}" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/contracts" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/contracts/{id}" to HttpMethod.Put) to Role(EmployeeRole.MANAGER),
            ("/api/contracts/{id}/instalments" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            // =====================================================================
            // Zuordnung — AllocationRoutes.kt
            // route("/api/allocations")
            // =====================================================================
            ("/api/allocations/queue" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/allocations/open-invoices" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/allocations/open-sales-invoices" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/allocations/open-instalments" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/allocations/telemetry" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/allocations" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/allocations/batch" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/allocations/{id}" to HttpMethod.Delete) to Role(EmployeeRole.MANAGER),
            // =====================================================================
            // Match suggestions — SuggestionRoutes.kt
            // route("/api/allocations") — read-only; accepting one is an allocation
            // =====================================================================
            ("/api/allocations/suggestions" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            // =====================================================================
            // Buchungsbeginn — BookkeepingStartRoutes.kt
            // route("/api/finance/bookkeeping-start")
            // =====================================================================
            ("/api/finance/bookkeeping-start" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/finance/bookkeeping-start" to HttpMethod.Put) to Role(EmployeeRole.MANAGER),
            ("/api/finance/bookkeeping-start/acknowledge" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            // =====================================================================
            // Our own bank accounts — BankAccountRoutes.kt
            // route("/api/bank-accounts") and route("/api/bank-statements")
            // =====================================================================
            ("/api/bank-accounts" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/bank-accounts/{id}" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/bank-accounts" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/bank-accounts/resolve" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/bank-statements/{id}/account" to HttpMethod.Put) to Role(EmployeeRole.MANAGER),
            // =====================================================================
            // Printer Discovery — PrinterDiscoveryRoutes.kt
            // route("/api/printer-discovery")
            // =====================================================================
            ("/api/printer-discovery/discover" to HttpMethod.Get) to Authenticated,
            ("/api/printer-discovery/status" to HttpMethod.Get) to Authenticated,
            ("/api/printer-discovery/formats" to HttpMethod.Get) to Authenticated,
            ("/api/printer-discovery/print-pdf" to HttpMethod.Post) to Authenticated,
            ("/api/printer-discovery/test-print/{printerLocationId}" to HttpMethod.Post) to Authenticated,
            // =====================================================================
            // Printer Location — PrinterLocationRoutes.kt
            // route("/api/printer-location")
            // =====================================================================
            ("/api/printer-location" to HttpMethod.Post) to Authenticated,
            ("/api/printer-location" to HttpMethod.Get) to Authenticated,
            ("/api/printer-location/{id}" to HttpMethod.Put) to Authenticated,
            ("/api/printer-location/{id}" to HttpMethod.Delete) to Authenticated,
            ("/api/printer-location/{id}/activate" to HttpMethod.Patch) to Authenticated,
            // =====================================================================
            // Print Job History — PrintJobHistoryRoutes.kt
            // route("/api/print-job-history")
            // =====================================================================
            ("/api/print-job-history" to HttpMethod.Get) to Authenticated,
            // =====================================================================
            // Terminals — TerminalRoutes.kt
            // route("/api/v1/terminals")
            // =====================================================================
            ("/api/v1/terminals/register" to HttpMethod.Post) to Authenticated,
            ("/api/v1/terminals/me" to HttpMethod.Get) to Authenticated,
            ("/api/v1/terminals/me-by-device" to HttpMethod.Get) to Authenticated,
            ("/api/v1/terminals/sync-tse" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/v1/terminals" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/v1/terminals" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/v1/terminals/{id}" to HttpMethod.Delete) to Role(EmployeeRole.MANAGER),
            ("/api/v1/terminals/{id}/tse-register" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/v1/terminals/{id}/reactivate" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/v1/terminals/pair" to HttpMethod.Post) to Public,
            ("/api/v1/terminals/{id}/pairing-code" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/v1/terminals/{id}/revoke" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            // =====================================================================
            // Backup & Restore — BackupRestoreRoutes.kt
            // route("/api/v1/backup")
            // =====================================================================
            ("/api/v1/backup/create" to HttpMethod.Post) to Role(EmployeeRole.ADMIN),
            ("/api/v1/backup/restore" to HttpMethod.Post) to Role(EmployeeRole.ADMIN),
            ("/api/v1/backup/config" to HttpMethod.Get) to Role(EmployeeRole.ADMIN),
            ("/api/v1/backup/config" to HttpMethod.Put) to Role(EmployeeRole.ADMIN),
            ("/api/v1/backup/create-to-server" to HttpMethod.Post) to Role(EmployeeRole.ADMIN),
            ("/api/v1/backup/restore-from-server" to HttpMethod.Post) to Role(EmployeeRole.ADMIN),
            ("/api/v1/backup/directories" to HttpMethod.Get) to Role(EmployeeRole.ADMIN),
            ("/api/v1/backup/list" to HttpMethod.Get) to Role(EmployeeRole.ADMIN),
            // =====================================================================
            // Time Tracking — TimeTrackingRoutes.kt
            // route("/api/timetracking")
            // =====================================================================
            ("/api/timetracking/clock-in" to HttpMethod.Post) to Authenticated,
            ("/api/timetracking/clock-out" to HttpMethod.Post) to Authenticated,
            ("/api/timetracking/break/start" to HttpMethod.Post) to Authenticated,
            ("/api/timetracking/break/end" to HttpMethod.Post) to Authenticated,
            ("/api/timetracking/active" to HttpMethod.Get) to Authenticated,
            ("/api/timetracking/current" to HttpMethod.Get) to Authenticated,
            ("/api/timetracking/my" to HttpMethod.Get) to Authenticated,
            ("/api/timetracking/overview" to HttpMethod.Get) to Authenticated,
            ("/api/timetracking/manual" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/timetracking/{id}" to HttpMethod.Put) to Authenticated,
            ("/api/timetracking/{id}" to HttpMethod.Delete) to Authenticated,
            ("/api/timetracking/team" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/timetracking/all" to HttpMethod.Get) to Role(EmployeeRole.ADMIN),
            ("/api/timetracking/my-entries-auth" to HttpMethod.Post) to Authenticated,
            ("/api/timetracking/employee/{employeeId}" to HttpMethod.Get) to Custom("self or manager"),
            ("/api/timetracking/employee/{employeeId}/overview" to HttpMethod.Get) to Custom("self or manager"),
            // =====================================================================
            // Time Approval — TimeApprovalRoutes.kt
            // route("/api/timetracking/approval")
            // =====================================================================
            ("/api/timetracking/approval/submit" to HttpMethod.Post) to Authenticated,
            ("/api/timetracking/approval/{id}/approve" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/timetracking/approval/{id}/reject" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/timetracking/approval/pending" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/timetracking/approval/my-status" to HttpMethod.Get) to Authenticated,
            // =====================================================================
            // DATEV Export (Time Tracking) — DatevExportRoutes.kt
            // route("/api/timetracking/datev")
            // DISABLED 2026-08-11 (timetracking): datevExportRoutes() is no longer registered in
            // Routing.kt, so these rules are inert. They are kept — together with the timetracking,
            // cost-centre and wage-type rules above — so that re-mounting the routes is a one-line
            // change when the feature redesign lands.
            // =====================================================================
            ("/api/timetracking/datev/preview" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/timetracking/datev/export" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/timetracking/datev/pdf" to HttpMethod.Get) to Authenticated,
            // =====================================================================
            // Cost Centers — CostCenterRoutes.kt
            // route("/api/costcenters")
            // =====================================================================
            ("/api/costcenters" to HttpMethod.Get) to Authenticated,
            ("/api/costcenters/{id}" to HttpMethod.Get) to Authenticated,
            ("/api/costcenters" to HttpMethod.Post) to Role(EmployeeRole.ADMIN),
            ("/api/costcenters/{id}" to HttpMethod.Put) to Role(EmployeeRole.ADMIN),
            ("/api/costcenters/{id}" to HttpMethod.Delete) to Role(EmployeeRole.ADMIN),
            // Plugin-architecture alias used by KtorTimeTrackingApiClient; CostCenterRoutes still mounts
            // at /api/costcenters, so a request to the plugin path passes auth and then 404s unless the
            // route is also moved/aliased. Permission rule is added first so default-deny stops masking
            // the routing mismatch.
            ("/api/plugins/timetracking-plugin/costcenters" to HttpMethod.Get) to Authenticated,
            ("/api/plugins/timetracking-plugin/costcenters/{id}" to HttpMethod.Get) to Authenticated,
            ("/api/plugins/timetracking-plugin/costcenters" to HttpMethod.Post) to Role(EmployeeRole.ADMIN),
            ("/api/plugins/timetracking-plugin/costcenters/{id}" to HttpMethod.Put) to Role(EmployeeRole.ADMIN),
            ("/api/plugins/timetracking-plugin/costcenters/{id}" to HttpMethod.Delete) to Role(EmployeeRole.ADMIN),
            // =====================================================================
            // Wage Types — WageTypeRoutes.kt
            // route("/api/wagetypes")
            // =====================================================================
            ("/api/wagetypes" to HttpMethod.Get) to Authenticated,
            ("/api/wagetypes/{id}" to HttpMethod.Get) to Authenticated,
            ("/api/wagetypes" to HttpMethod.Post) to Role(EmployeeRole.ADMIN),
            ("/api/wagetypes/{id}" to HttpMethod.Put) to Role(EmployeeRole.ADMIN),
            ("/api/wagetypes/{id}" to HttpMethod.Delete) to Role(EmployeeRole.ADMIN),
            // =====================================================================
            // Admin — AdminRoutes.kt
            // route("/api/admin")
            // =====================================================================
            ("/api/admin/permissions" to HttpMethod.Get) to Role(EmployeeRole.ADMIN),
            // =====================================================================
            // TSE Admin — TseAdminRoutes.kt (Protected module — inline auth)
            // route("/api/tse")
            // =====================================================================
            ("/api/tse/config" to HttpMethod.Get) to Role(EmployeeRole.ADMIN),
            ("/api/tse/config" to HttpMethod.Put) to Role(EmployeeRole.ADMIN),
            ("/api/tse/status" to HttpMethod.Get) to Role(EmployeeRole.ADMIN),
            ("/api/tse/info" to HttpMethod.Get) to Role(EmployeeRole.ADMIN),
            ("/api/tse/health" to HttpMethod.Get) to Role(EmployeeRole.ADMIN),
            ("/api/tse/flashhealth" to HttpMethod.Get) to Role(EmployeeRole.ADMIN),
            ("/api/tse/ping" to HttpMethod.Get) to Role(EmployeeRole.ADMIN),
            ("/api/tse/selftest" to HttpMethod.Post) to Role(EmployeeRole.ADMIN),
            ("/api/tse/updatetime" to HttpMethod.Post) to Role(EmployeeRole.ADMIN),
            ("/api/tse/export/full" to HttpMethod.Post) to Role(EmployeeRole.ADMIN),
            ("/api/tse/export/incremental" to HttpMethod.Post) to Role(EmployeeRole.ADMIN),
            ("/api/tse/export/time-filtered" to HttpMethod.Post) to Role(EmployeeRole.ADMIN),
            ("/api/tse/export/transaction-filtered" to HttpMethod.Post) to Role(EmployeeRole.ADMIN),
            ("/api/tse/export/size" to HttpMethod.Get) to Role(EmployeeRole.ADMIN),
            ("/api/tse/export" to HttpMethod.Delete) to Role(EmployeeRole.ADMIN),
            ("/api/tse/verify" to HttpMethod.Post) to Role(EmployeeRole.ADMIN),
            ("/api/tse/setup" to HttpMethod.Post) to Role(EmployeeRole.ADMIN),
            ("/api/tse/factory-reset" to HttpMethod.Post) to Role(EmployeeRole.ADMIN),
            ("/api/tse/restart" to HttpMethod.Post) to Role(EmployeeRole.ADMIN),
            ("/api/tse/decommission" to HttpMethod.Post) to Role(EmployeeRole.ADMIN),
            ("/api/tse/csp-export/disable" to HttpMethod.Post) to Role(EmployeeRole.ADMIN),
            ("/api/tse/entries/browse" to HttpMethod.Post) to Role(EmployeeRole.ADMIN),
            ("/api/tse/export/lcm" to HttpMethod.Post) to Role(EmployeeRole.ADMIN),
            ("/api/tse/firmware-check" to HttpMethod.Get) to Role(EmployeeRole.ADMIN),
            ("/api/tse/firmware-apply-bundled" to HttpMethod.Post) to Role(EmployeeRole.ADMIN),
            ("/api/tse/clients" to HttpMethod.Get) to Role(EmployeeRole.ADMIN),
            ("/api/tse/transactions" to HttpMethod.Get) to Role(EmployeeRole.ADMIN),
            ("/api/tse/last-transaction" to HttpMethod.Get) to Role(EmployeeRole.ADMIN),
            ("/api/tse/admin/unlock" to HttpMethod.Post) to Role(EmployeeRole.ADMIN),
            ("/api/tse/admin/lock" to HttpMethod.Post) to Role(EmployeeRole.ADMIN),
            ("/api/tse/admin/status" to HttpMethod.Get) to Role(EmployeeRole.ADMIN),
            // =====================================================================
            // TSE Signing Queue — TseSigningQueueRoutes.kt (Protected module — inline auth)
            // route("/api/tse/queue")
            // =====================================================================
            ("/api/tse/queue/status" to HttpMethod.Get) to Role(EmployeeRole.ADMIN),
            ("/api/tse/queue/entries" to HttpMethod.Get) to Role(EmployeeRole.ADMIN),
            ("/api/tse/queue/entries/{id}" to HttpMethod.Get) to Role(EmployeeRole.ADMIN),
            ("/api/tse/queue/entries/{id}/retry" to HttpMethod.Post) to Role(EmployeeRole.ADMIN),
            ("/api/tse/queue/entries/{id}/cancel" to HttpMethod.Post) to Role(EmployeeRole.ADMIN),
            ("/api/tse/queue/pause" to HttpMethod.Post) to Role(EmployeeRole.ADMIN),
            ("/api/tse/queue/resume" to HttpMethod.Post) to Role(EmployeeRole.ADMIN),
            ("/api/tse/queue/trigger" to HttpMethod.Post) to Role(EmployeeRole.ADMIN),
            ("/api/tse/queue/cleanup" to HttpMethod.Post) to Role(EmployeeRole.ADMIN),
            // =====================================================================
            // Reservations — ReservationRoutes.kt
            // route("/api/reservations")
            // =====================================================================
            ("/api/reservations/search" to HttpMethod.Get) to Authenticated,
            ("/api/reservations/availability" to HttpMethod.Get) to Authenticated,
            ("/api/reservations" to HttpMethod.Get) to Authenticated,
            ("/api/reservations/{id}" to HttpMethod.Get) to Authenticated,
            ("/api/reservations" to HttpMethod.Post) to Authenticated,
            ("/api/reservations/{id}" to HttpMethod.Put) to Authenticated,
            ("/api/reservations/{id}/status" to HttpMethod.Put) to Authenticated,
            ("/api/reservations/{id}" to HttpMethod.Delete) to Role(EmployeeRole.MANAGER),
            // =====================================================================
            // Reservation Rules — ReservationRuleRoutes.kt
            // route("/api/reservation-rules")
            // =====================================================================
            ("/api/reservation-rules" to HttpMethod.Get) to Authenticated,
            ("/api/reservation-rules/{id}" to HttpMethod.Get) to Authenticated,
            ("/api/reservation-rules" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/reservation-rules/{id}" to HttpMethod.Put) to Role(EmployeeRole.MANAGER),
            ("/api/reservation-rules/{id}" to HttpMethod.Delete) to Role(EmployeeRole.MANAGER),
            // =====================================================================
            // Opening Hours — OpeningHoursRoutes.kt
            // route("/api/opening-hours")
            // =====================================================================
            ("/api/opening-hours" to HttpMethod.Get) to Authenticated,
            ("/api/opening-hours" to HttpMethod.Put) to Role(EmployeeRole.MANAGER),
            ("/api/opening-hours/overrides" to HttpMethod.Get) to Authenticated,
            ("/api/opening-hours/overrides" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/opening-hours/overrides/{id}" to HttpMethod.Put) to Role(EmployeeRole.MANAGER),
            ("/api/opening-hours/overrides/{id}" to HttpMethod.Delete) to Role(EmployeeRole.MANAGER),
            // =====================================================================
            // Combinable Tables — RTableRoutes.kt (combinable endpoints)
            // =====================================================================
            ("/api/tables/combinable" to HttpMethod.Get) to Authenticated,
            ("/api/tables/combinable" to HttpMethod.Put) to Role(EmployeeRole.MANAGER),
            ("/api/tables/{id}/combinable" to HttpMethod.Get) to Authenticated,
            ("/api/tables/{id}/combinable" to HttpMethod.Put) to Role(EmployeeRole.MANAGER),
            // =====================================================================
            // Reservation Table Assignments — ReservationRoutes.kt
            // =====================================================================
            ("/api/reservations/bulk-auto-assign" to HttpMethod.Post) to Authenticated,
            ("/api/reservations/{id}/auto-assign" to HttpMethod.Post) to Authenticated,
            ("/api/reservations/{id}/assign-tables" to HttpMethod.Post) to Authenticated,
            ("/api/reservations/{id}/unassign-tables" to HttpMethod.Delete) to Role(EmployeeRole.MANAGER),
            ("/api/reservations/{id}/tables" to HttpMethod.Get) to Authenticated,
            // =====================================================================
            // Location Types — LocationTypeRoutes.kt
            // route("/api/location-types")
            // =====================================================================
            ("/api/location-types" to HttpMethod.Get) to Authenticated,
            ("/api/location-types/{id}" to HttpMethod.Get) to Authenticated,
            ("/api/location-types" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/location-types/{id}" to HttpMethod.Put) to Role(EmployeeRole.MANAGER),
            ("/api/location-types/{id}" to HttpMethod.Delete) to Role(EmployeeRole.MANAGER),
            // =====================================================================
            // Scheduled Jobs — ScheduledJobRoutes.kt
            // route("/api/scheduled-jobs")
            // =====================================================================
            ("/api/scheduled-jobs" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/scheduled-jobs/types" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/scheduled-jobs/{id}" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            ("/api/scheduled-jobs/{id}/run" to HttpMethod.Post) to Role(EmployeeRole.MANAGER),
            ("/api/scheduled-jobs/{id}/enabled" to HttpMethod.Put) to Role(EmployeeRole.MANAGER),
            ("/api/scheduled-jobs/{id}/executions" to HttpMethod.Get) to Role(EmployeeRole.MANAGER),
            // =====================================================================
            // Changelog — ChangelogRoutes.kt
            // route("/api/v1/changelog")
            // =====================================================================
            ("/api/v1/changelog" to HttpMethod.Get) to Authenticated,
        )
}
