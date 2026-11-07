package org.example.project.infrastructure.plugins

import io.ktor.http.CacheControl
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.OpenApiDoc
import io.ktor.openapi.OpenApiInfo
import io.ktor.server.application.Application
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.request.queryString
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.IgnoreTrailingSlash
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.OpenApiDocSource
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.routes.notification.notificationStreamRoutes
import org.example.project.database.DatabaseFactory
import org.example.project.database.DatabaseFactory.getConnectionPoolStats
import org.example.project.features.admin.domain.routes.adminRoutes
import org.example.project.features.allocation.domain.routes.allocationRoutes
import org.example.project.features.audit.domain.routes.changeFeedRoutes
import org.example.project.features.audit.domain.routes.transactionAuditRoutes
import org.example.project.features.auth.domain.routes.approvalRoutes
import org.example.project.features.auth.domain.routes.authRoutes
import org.example.project.features.auth.domain.routes.sessionAdminRoutes
import org.example.project.features.auth.domain.routes.temporarySessionRoutes
import org.example.project.features.backup.domain.routes.backupRestoreRoutes
import org.example.project.features.bank.domain.routes.bankAccountRoutes
import org.example.project.features.bill.domain.routes.billRoutes
import org.example.project.features.cardTerminal.domain.routes.cardPaymentSseRoutes
import org.example.project.features.cardTerminal.domain.routes.cardSettlementRoutes
import org.example.project.features.cardTerminal.domain.routes.cardTerminalRoutes
import org.example.project.features.changelog.domain.routes.changelogRoutes
import org.example.project.features.clienterror.domain.routes.clientErrorRoutes
import org.example.project.features.contract.domain.routes.contractRoutes
import org.example.project.features.customer.domain.routes.customerAddressRoutes
import org.example.project.features.customer.domain.routes.customerRoutes
import org.example.project.features.customexport.domain.routes.customExportRoutes
import org.example.project.features.datevexportlog.domain.routes.datevCadenceExportRoutes
import org.example.project.features.datevexportlog.domain.routes.datevExportLogRoutes
import org.example.project.features.debtor.domain.routes.debtorRoutes
import org.example.project.features.document.domain.routes.documentRoutes
import org.example.project.features.document.domain.routes.invoiceRoutes
import org.example.project.features.dsfinvk.domain.routes.dsfinvkExportRoutes
import org.example.project.features.employee.domain.routes.employeeInfoRoutes
import org.example.project.features.employee.domain.routes.employeeRoutes
import org.example.project.features.employee.domain.routes.permissionGroupRoutes
import org.example.project.features.employee.domain.routes.workScheduleRoutes
import org.example.project.features.finance.domain.routes.bookkeepingStartRoutes
import org.example.project.features.issuereport.domain.routes.issueReportRoutes
import org.example.project.features.kassenbuch.domain.routes.cashBookTransferRoutes
import org.example.project.features.kassenbuch.domain.routes.datevKassenbuchRoutes
import org.example.project.features.kassenbuch.domain.routes.kassenbuchRoutes
import org.example.project.features.maintenance.MaintenancePolicy
import org.example.project.features.maintenance.domain.routes.maintenanceRoutes
import org.example.project.features.matching.domain.routes.suggestionRoutes
import org.example.project.features.monthlyclose.domain.routes.monthlyCloseRoutes
import org.example.project.features.openinghours.domain.routes.openingHoursRoutes
import org.example.project.features.order.domain.routes.orderItemRoutes
import org.example.project.features.order.domain.routes.orderRoutes
import org.example.project.features.partner.domain.routes.partnerRoutes
import org.example.project.features.payment.domain.routes.paymentTypeRoutes
import org.example.project.features.pfand.domain.routes.pfandTypeRoutes
import org.example.project.features.printer.domain.routes.printJobHistoryRoutes
import org.example.project.features.printer.domain.routes.printerDiscoveryRoutes
import org.example.project.features.printer.domain.routes.printerLocationRoutes
import org.example.project.features.product.domain.routes.choiceGroupRoutes
import org.example.project.features.product.domain.routes.productCategoryRoutes
import org.example.project.features.product.domain.routes.productChoiceGroupRoutes
import org.example.project.features.product.domain.routes.productExtrasAssignmentRoutes
import org.example.project.features.product.domain.routes.productExtrasRoutes
import org.example.project.features.product.domain.routes.productRoutes
import org.example.project.features.promotion.domain.routes.promotionRoutes
import org.example.project.features.reservation.domain.routes.reservationRoutes
import org.example.project.features.reservation.domain.routes.reservationRuleRoutes
import org.example.project.features.salesinvoice.domain.routes.salesInvoiceRoutes
import org.example.project.features.scheduler.domain.routes.scheduledJobRoutes
import org.example.project.features.setup.domain.routes.accountingConfigurationRoutes
import org.example.project.features.setup.domain.routes.backofficeConfigurationRoutes
import org.example.project.features.setup.domain.routes.configurationRoutes
import org.example.project.features.setup.domain.routes.ecrConfigurationRoutes
import org.example.project.features.setup.domain.routes.entryLogRoutes
import org.example.project.features.setup.domain.routes.fiscalConfigurationRoutes
import org.example.project.features.setup.domain.routes.initialSetupRoutes
import org.example.project.features.setup.domain.routes.shopInfoRoutes
import org.example.project.features.setup.domain.routes.vatRoutes
import org.example.project.features.startpage.startPageRoutes
import org.example.project.features.statistics.domain.routes.statisticsRoutes
import org.example.project.features.supplier.domain.routes.supplierRoutes
import org.example.project.features.table.domain.routes.billingRequestRoutes
import org.example.project.features.table.domain.routes.locationTypeRoutes
import org.example.project.features.table.domain.routes.rTableRoutes
import org.example.project.features.table.domain.routes.roomElementRoutes
import org.example.project.features.terminal.domain.routes.terminalRoutes
import org.example.project.features.tse.domain.routes.tseAdminRoutes
import org.example.project.features.tse.domain.routes.tseSigningQueueRoutes
import org.example.project.features.update.domain.routes.clientUpdateRoutes
import org.example.project.features.update.domain.routes.updateRoutes
import org.example.project.features.voucher.domain.routes.voucherRoutes
import org.example.project.features.zBon.domain.routes.zBonRoutes
import org.koin.ktor.ext.getKoin
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("Application.configureRouting")

/**
 * CSP for the browser till. `'wasm-unsafe-eval'` is what lets the Kotlin/Wasm module compile;
 * `'unsafe-inline'` styles are needed by the generated index.html. `connect-src 'self'` holds
 * because the page talks only to its own origin (API + SSE). `frame-ancestors 'none'` forbids
 * framing the till.
 */
private const val POS_WEB_CONTENT_SECURITY_POLICY =
    "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; connect-src 'self'; " +
        "img-src 'self' data:; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'"

private const val CONTENT_SECURITY_POLICY_HEADER = "Content-Security-Policy"

/**
 * Sets the till's CSP and 404s every request under its route unless the shop has enabled at least one
 * browser station role.
 */
private val posWebGate =
    createRouteScopedPlugin("BrowserStationsGate") {
        val configManager = application.getKoin().get<IShopConfigurationManager>()
        onCall { call ->
            call.response.header(CONTENT_SECURITY_POLICY_HEADER, POS_WEB_CONTENT_SECURITY_POLICY)
            if (configManager.getBrowserStationRoles().isEmpty()) call.respond(HttpStatusCode.NotFound)
        }
    }

/**
 * CSP for the maintainer page. `'unsafe-eval'` is what Alpine's standard build needs (it compiles
 * expressions with `new Function`); everything else stays closed: no inline script or style, no
 * external origin, no framing. Log text is attacker-influenced, so the page renders it with `x-text`
 * only (`MaintenancePageTest` enforces that), and this policy is the second line behind that rule.
 */
private const val MAINTENANCE_CONTENT_SECURITY_POLICY =
    "default-src 'self'; script-src 'self' 'unsafe-eval'; style-src 'self'; img-src 'self' data:; " +
        "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"

/** Sets the page's CSP and 404s every request under it unless the maintainer account is configured. */
private val maintenanceWebGate =
    createRouteScopedPlugin("MaintenanceWebGate") {
        onCall { call ->
            call.response.header(CONTENT_SECURITY_POLICY_HEADER, MAINTENANCE_CONTENT_SECURITY_POLICY)
            if (!MaintenancePolicy.enabled) call.respond(HttpStatusCode.NotFound)
        }
    }

/**
 * 404s the API documentation unless [ApiDocsPolicy] says it is on. 404, not 403: a switched-off feature
 * does not announce that it exists. Read per request, so the Web-Admin switch applies without a restart.
 */
private val apiDocsGate =
    createRouteScopedPlugin("ApiDocsGate") {
        val configManager = application.getKoin().get<IShopConfigurationManager>()
        onCall { call ->
            if (!ApiDocsPolicy.current(configManager).enabled) call.respond(HttpStatusCode.NotFound)
        }
    }

/**
 * Configures application routing and endpoints.
 *
 * Sets up all HTTP routes and their handlers. Currently includes a basic
 * health check endpoint and greeting endpoint for testing.
 */
fun Application.configureRouting() {
    install(IgnoreTrailingSlash)
    routing {
        /**
         * Check that the server is alive.
         *
         * Description: Public and unauthenticated, for load balancers and monitoring. Answers 200 with a plain-text line in every case — the body says whether the database is connected and carries the connection-pool statistics, so a database outage is reported in the text rather than as a failed status code.
         *
         * Tag: infrastructure
         */
        get("/health") {
            try {
                // Test database connection if initialized
                if (DatabaseFactory.isInitialized()) {
                    val stats = getConnectionPoolStats()
                    call.respondText("OK - Database connected. Pool stats: $stats")
                } else {
                    call.respondText("OK - Application running (database not initialized)")
                }
            } catch (e: Exception) {
                logger.error("Health check failed", e)
                call.respondText("ERROR - Database connection failed: ${e.message}")
            }
        }

        // `/` (start page) and `/start-page` — see StartPageRoutes and WebEntryRegistry.
        startPageRoutes()

        // The staticResources mounts below share one OpenAPI description on purpose. The
        // compiler plugin has no handler lambda to attach per-call metadata to, so whichever KDoc
        // it resolves last is applied to all of them — giving each its own block produced a spec
        // where /manual and /font were both documented as the admin panel. The shared text is
        // therefore written to be true of all three; keep it that way, and put per-mount notes in
        // ordinary comments like these.

        /**
         * Serve static content.
         *
         * Description: Public and unauthenticated static resources — the operator manual under /manual (start at /manual/index.html), the web admin panel's CJK font under /font, the panel itself under /admin, and the browser till under /pos, which answers 404 unless the shop enabled a browser station role and the build included the bundle. None of them require a token, because the manual and both clients' login screens are reachable before anyone has signed in.
         *
         * Tag: infrastructure
         */
        staticResources("/manual", "manual")

        staticResources("/font", "static/font") {
            // CJK font for the admin panel. Compose/wasm draws through Skia, which never consults
            // the browser's CSS font registry, so the panel fetches the bytes and hands them to
            // Compose — a <link rel=stylesheet> would load the font and still render tofu.
            // Served from here rather than a CDN because shops frequently run isolated from the
            // internet, and bundling it would put ~8 MB into the wasm output for a language most
            // installs never select.
            //
            // The cache header is load-bearing rather than an optimisation: staticResources sends
            // none by default, so without this the browser re-fetches all 8 MB on every page load
            // and a Chinese user watches the UI flash tofu each time. A new font would ship under a
            // new name, so a year is safe.
            cacheControl {
                listOf(CacheControl.MaxAge(maxAgeSeconds = 31536000, visibility = CacheControl.Visibility.Public))
            }
        }

        browserAppStaticResources("/admin", "admin") {
            // Web admin panel (adminApp — Compose Multiplatform / wasmJs), served at /admin/.
            //
            // Cache policy, same as /pos/ below: content-hashed .wasm chunks for a year; EVERYTHING
            // else is `no-cache`, so a redeploy is picked up at once. Every file carries a
            // content-hash ETag (browserAppStaticResources -> StaticResourceValidators) and the
            // ConditionalHeaders plugin answers a matching If-None-Match with 304, so `no-cache`
            // is a cheap revalidation, not a full download. The header also pins the policy: it rules out
            // any browser caching of a file whose name does not change across releases. That
            // matters beyond index.html/adminApp.js: the lazily loaded PDF.js chunks (e.g. 604.js,
            // 824.js — one of them is the worker) have stable numeric names, so a cached old
            // worker after a pdfjs-dist upgrade would fail PDF.js's API/worker version check and
            // every PDF would stop opening.
            // The production bundle is folded into this JAR's resources only when packaging for
            // deployment (see the `bundleAdminPanel` flag in pos-server/build.gradle.kts); in dev
            // the panel runs from :ui-backoffice:wasmJsBrowserDevelopmentRun.
            //
            // Everything the panel loads — adminApp.js, the content-hashed .wasm chunks, and
            // composeResources/** — is fetched by relative URL resolved against document.baseURI.
            // The build injects <base href="/admin/"> into index.html so those resolve under
            // /admin/ whether the browser lands on /admin or /admin/, while the panel's absolute
            // /api and /font calls are unaffected.
            cacheControl { url ->
                if (url.toString().endsWith(".wasm")) {
                    listOf(CacheControl.MaxAge(maxAgeSeconds = 31536000, visibility = CacheControl.Visibility.Public))
                } else {
                    listOf(CacheControl.NoCache(null))
                }
            }
        }

        // The till client compiled for wasm (composeApp), served at /pos/ as a browser station.
        // Same origin as the API on purpose, so no CORS plugin is needed and the page cannot become
        // a way for an arbitrary origin to reach /api.
        //
        // Gated: the route answers 404 until the shop enables at least one browser role
        // (`browser_stations` setting), so a shop that never opted in does not expose a till page.
        // The gate reads the live setting per request, so a change takes effect without a restart.
        //
        // The bundle is in this JAR only when built with packageForDeployment/jpackageServer or
        // -PbundlePosWeb=true (see pos-server/build.gradle.kts); in a plain dev run the mount
        // serves nothing. Dev runs the client from :ui-till:wasmJsBrowserDevelopmentRun on
        // port 8082 instead. That works because the wasm client defaults its API base URL to the
        // page's own origin (DefaultApiBaseUrl.wasmJs.kt) and
        // ui/till/webpack.config.d/devServerProxy.js forwards :8082/api to this server -- so the
        // browser still only ever talks to one origin, and the no-CORS property holds in dev too.
        //
        // Cache policy: content-hashed .wasm chunks for a year; EVERYTHING else is `no-cache`.
        // Files carry a content-hash ETag and revalidate with a 304 (see /admin above), so
        // `no-cache` stays cheap. Never give composeApp.js a max-age: a cached copy after the
        // update banner's reload would report the old version and re-trigger the banner forever.
        route("/pos") {
            install(posWebGate)
            // Through browserAppStaticResources so the page carries the same CSP / security headers as /admin.
            browserAppStaticResources("/", "pos-web") {
                cacheControl { url ->
                    if (url.toString().endsWith(".wasm")) {
                        listOf(CacheControl.MaxAge(maxAgeSeconds = 31536000, visibility = CacheControl.Visibility.Public))
                    } else {
                        listOf(CacheControl.NoCache(null))
                    }
                }
            }
        }

        // The maintainer page (log search). Static, vendored framework, no build step. Not listed on the
        // start page and not in WebEntryRegistry on purpose: it must not advertise itself. 404 while the
        // maintainer account is unconfigured, read per request.
        route("/maintenance") {
            install(maintenanceWebGate)
            staticResources("/", "maintenance") {
                cacheControl { listOf(CacheControl.NoCache(null)) }
            }
        }

        // The till was served at /pos-preview/ before it became a supported browser station; a
        // bookmarked URL keeps working.
        get("/pos-preview/{rest...}") {
            // Keep the query: a pairing link `/pos-preview/?code=...` must land on `/pos/?code=...`.
            val query = call.request.queryString()
            call.respondRedirect(if (query.isEmpty()) "/pos/" else "/pos/?$query", permanent = true)
        }

        route("openapi") {
            install(apiDocsGate)

            /**
             * Get this API's OpenAPI specification.
             *
             * Description: The specification as JSON, generated from the server's own routing tree, so it always describes the endpoints this build actually serves. /swagger renders the same document interactively. Answers 404 unless the API documentation is switched on (Web-Admin → Einstellungen → System, or `POS_API_DOCS`).
             *
             * Tag: infrastructure
             */
            get {
                // This replaced the hand-written openapi/documentation.yaml (with its paths/ and
                // components/ sub-files, and the /swagger/paths + /swagger/components static mounts
                // that resolved their $refs) once every path the YAML documented was also produced
                // here. Summaries, tags, parameter descriptions and the 4xx/5xx set come from the KDoc
                // block above each route handler; see
                // docs/superpowers/plans/2026-08-11-route-kdoc-openapi.md for the syntax, and the
                // OpenApiGenerationTest gate that fails the build when an /api route is missing one.
                val generated =
                    OpenApiDocSource
                        .Routing()
                        .read(
                            call.application,
                            OpenApiDoc(info = OpenApiInfo(title = OPENAPI_TITLE, version = OPENAPI_VERSION)),
                        )
                call.respondText(generated.content, generated.contentType)
            }
        }

        route("swagger") {
            install(apiDocsGate)

            /**
             * Browse the API in Swagger UI.
             *
             * Description: Interactive documentation for the same generated specification, at /swagger. The document it fetches is mounted alongside it at /swagger/openapi.json. Answers 404 unless the API documentation is switched on (Web-Admin → Einstellungen → System, or `POS_API_DOCS`).
             *
             * Tag: infrastructure
             */
            swaggerUI(path = "") {
                source = OpenApiDocSource.Routing()
                remotePath = "openapi.json"
                info = OpenApiInfo(title = OPENAPI_TITLE, version = OPENAPI_VERSION)
            }
        }

        route("openapi-generated.json") {
            install(apiDocsGate)

            /**
             * Get this API's OpenAPI specification (alias).
             *
             * Description: Identical to /openapi. Kept under its original name so existing tooling keeps working. Answers 404 unless the API documentation is switched on (Web-Admin → Einstellungen → System, or `POS_API_DOCS`).
             *
             * Tag: infrastructure
             */
            get {
                // The name dates from the period when this served the generated spec side by side with
                // the hand-written YAML. OpenApiGenerationTest and scripts/openapi_coverage.py read it.
                val generated =
                    OpenApiDocSource
                        .Routing()
                        .read(
                            call.application,
                            OpenApiDoc(info = OpenApiInfo(title = OPENAPI_TITLE, version = OPENAPI_VERSION)),
                        )
                call.respondText(generated.content, generated.contentType)
            }
        }

        // Initial Setup routes (unauthenticated — no user exists before setup)
        initialSetupRoutes()

        // Authentication routes (login, logout, token refresh)
        authRoutes()

        // Session administration routes (daily-reset policy + panic revoke-all)
        sessionAdminRoutes()

        // Hybrid Authorization System routes
        temporarySessionRoutes() // Temporary manager sessions (5-minute device-specific)
        approvalRoutes() // Approval requests (real-time manager authorization)

        // Shop Information API routes
        shopInfoRoutes()

        // Configuration API routes (key-value based)
        configurationRoutes()
        ecrConfigurationRoutes()
        fiscalConfigurationRoutes()
        accountingConfigurationRoutes()
        backofficeConfigurationRoutes()

        // Entry Log API routes (operation audit trail for sync)
        entryLogRoutes()

        // Change Feed API routes (seq-based polling for client sync)
        changeFeedRoutes()

        // Transaction Audit API routes (comprehensive transaction logging)
        transactionAuditRoutes()

        // Client-reported errors (device-token gated, no JWT — see ClientErrorRoutes)
        clientErrorRoutes()

        // Maintainer account (log search; off unless configured — see MaintenanceRoutes)
        maintenanceRoutes()

        // Order Management API routes
        orderRoutes()
        orderItemRoutes()

        // VAT Management API routes
        vatRoutes()

        // Payment Type Management API routes
        paymentTypeRoutes()

        // Employee Management API routes
        employeeRoutes()
        permissionGroupRoutes()

        // Employee Information Management API routes
        employeeInfoRoutes()

        // Work Schedule Management API routes
        workScheduleRoutes()

        // Product Management API routes
        productCategoryRoutes()
        productExtrasRoutes()
        productExtrasAssignmentRoutes()
        choiceGroupRoutes()
        productChoiceGroupRoutes()
        productRoutes()
        pfandTypeRoutes()

        // Changelog API routes
        changelogRoutes()

        // Restaurant Table Management API routes
        rTableRoutes()

        // Recoverable billing requests (reconcile-on-connect)
        billingRequestRoutes()

        // Room Element Management API routes (Floor Plan)
        roomElementRoutes()

        // Location Type Management API routes
        locationTypeRoutes()

        // Voucher Management API routes
        voucherRoutes()

        // Promotion Management API routes
        promotionRoutes()

        // Bill Management API routes
        billRoutes()

        // Z-report Management API routes
        zBonRoutes()

        // Statistics & Analytics API routes
        statisticsRoutes()

        // Kassenbuch API routes
        kassenbuchRoutes()
        datevKassenbuchRoutes()

        // Cash-book-to-cash-book transfer routes (Geldtransit)
        cashBookTransferRoutes()

        // Ausgangsrechnungen — create from a bill, issue, cancel, download the archived PDF
        salesInvoiceRoutes()

        // DATEV Export Log API routes
        datevExportLogRoutes()

        // DATEV Cadence & Ad-hoc Export API routes
        datevCadenceExportRoutes()

        // Monthly Close API routes
        monthlyCloseRoutes()

        // DSFinV-K Export API routes (German fiscal compliance CSV export)
        dsfinvkExportRoutes()

        // TSE Admin API routes (for client app TSE management)
        tseAdminRoutes()

        // TSE Signing Queue API routes (for Nachsignieren monitoring and management)
        tseSigningQueueRoutes()

        cardTerminalRoutes()
        cardSettlementRoutes()
        cardPaymentSseRoutes()
        // Terminal Management API routes (for multi-client DSFinV-K compliance)
        terminalRoutes()

        // Customer Management API routes
        customerRoutes()
        customerAddressRoutes()

        // Printer Location Management API routes
        printerLocationRoutes()

        // Printer Discovery API routes (CUPS/IPP printer detection)
        printerDiscoveryRoutes()

        // Print Job History API routes (audit trail)
        printJobHistoryRoutes()

        // Document Management API routes
        documentRoutes()

        // Normalized invoice aggregate — writes keyed on invoice id, see InvoiceRoutes.kt
        invoiceRoutes()

        // Creditor master data
        supplierRoutes()
        debtorRoutes()
        partnerRoutes()

        // Operator-defined extracts — the catalogue, the saved definitions and the CSV runs
        customExportRoutes()

        // Recurring obligations and their expected payments
        contractRoutes()

        // Zuordnung — the assignment queue, its candidate lists and the writes that settle them
        allocationRoutes()

        // What a payment probably settles — the match ladder, read-only
        suggestionRoutes()

        // Our own bank accounts, and manual statement assignment
        bankAccountRoutes()

        // The Buchungsbeginn — the cut-off both work lists are filtered by
        bookkeepingStartRoutes()

        // SSE notification stream (replaces the WebSocket notification transport)
        notificationStreamRoutes()

        // Backup and Restore routes (database backup/restore for data export)
        backupRestoreRoutes()

        // Time tracking routes
        // DISABLED 2026-08-11 (timetracking): the feature is being redesigned and ships disabled
        // for the next release. The client called these under an /api/plugins/timetracking-plugin
        // prefix the server never served, so nothing here was reachable in practice. Feature code,
        // DI wiring and RoutePermissions rules are preserved for the redesign.
        // timeTrackingRoutes()
        // timeApprovalRoutes()
        // costCenterRoutes()
        // wageTypeRoutes()
        // datevExportRoutes()

        // Reservation Management API routes
        reservationRoutes()
        reservationRuleRoutes()

        // Opening Hours routes
        openingHoursRoutes()

        // Scheduled Jobs routes (cron-style user-configurable tasks)
        scheduledJobRoutes()

        // Software update routes (GitHub release check, staging and install)
        updateRoutes()

        // Android build for tills -- public on purpose, see ClientUpdateRoutes
        clientUpdateRoutes()

        // Problem reports (GitHub issues)
        issueReportRoutes()

        // Admin routes
        adminRoutes()
    }
}

/** Title written into the `info` block of every generated OpenAPI document this server serves. */
private const val OPENAPI_TITLE = "POS System API"

/** Version written into the `info` block of the generated OpenAPI document. */
private const val OPENAPI_VERSION = "1.0.0"
