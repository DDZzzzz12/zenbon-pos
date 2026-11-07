package org.example.project.di

import io.ktor.client.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.example.project.BuildKonfig
import org.example.project.core.auth.PermissionManager
import org.example.project.core.auth.TokenStore
import org.example.project.core.browser.PageReloaderStampStore
import org.example.project.core.browser.ReloadLoopGuard
import org.example.project.core.browser.ServerBuildWatcher
import org.example.project.core.browser.isBrowserClient
import org.example.project.core.data.database.LocalDatabaseManager
import org.example.project.core.data.remote.*
import org.example.project.core.data.remote.impl.*
import org.example.project.core.data.remote.impl.KtorBackupApiClient
import org.example.project.core.data.remote.impl.KtorDsfinvkApiClient
import org.example.project.core.data.remote.impl.KtorTerminalApiClient
import org.example.project.core.data.remote.impl.KtorTimeTrackingApiClient
import org.example.project.core.network.HttpCall
import org.example.project.core.network.KermitKtorLogger
import org.example.project.core.network.LocalDbHttpCall
import org.example.project.core.network.NotificationConnection
import org.example.project.core.network.NotificationReconciler
import org.example.project.core.network.ReconnectPolicy
import org.example.project.core.network.SseNotificationConnection
import org.example.project.core.network.createPlatformEngine
import org.example.project.core.network.createPosHttpClient
import org.example.project.core.network.httpLogLevel
import org.example.project.core.network.installTillClientPlugins
import org.example.project.core.network.platformHttpClientConfig
import org.example.project.core.presentation.error.ClientErrorReporter
import org.example.project.core.presentation.error.DefaultClientErrorReporter
import org.example.project.features.issuereport.data.remote.IssueReportApiClient
import org.example.project.features.issuereport.data.remote.impl.KtorIssueReportApiClient
import org.example.project.features.launch.presentation.TerminalColdResetBus
import org.example.project.features.launch.presentation.TerminalResetKind
import org.example.project.features.openinghours.data.remote.OpeningHoursApiClient
import org.example.project.features.openinghours.data.remote.impl.KtorOpeningHoursApiClient
import org.example.project.features.reservation.data.remote.ReservationApiClient
import org.example.project.features.reservation.data.remote.impl.KtorReservationApiClient
import org.example.project.features.scheduler.data.remote.ScheduledJobApiClient
import org.example.project.features.scheduler.data.remote.impl.KtorScheduledJobApiClient
import org.example.project.features.settings.data.remote.LocationTypeApiClient
import org.example.project.features.settings.data.remote.impl.KtorLocationTypeApiClient
import org.example.project.features.setup.data.remote.KtorSetupApiClient
import org.example.project.features.setup.data.remote.SetupApiClient
import org.example.project.features.table.data.remote.BillingRequestApiClient
import org.example.project.features.table.data.remote.KtorBillingRequestApiClient
import org.example.project.features.update.data.remote.AppUpdateApiClient
import org.example.project.features.update.data.remote.UpdateApiClient
import org.example.project.features.update.data.remote.impl.KtorAppUpdateApiClient
import org.example.project.features.update.data.remote.impl.KtorUpdateApiClient
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module
import kotlin.time.Clock

/**
 * Network dependency injection module.
 *
 * Provides HTTP client and related networking components
 * with platform-specific implementations.
 */
val networkModule =
    module {

        /**
         * Main HTTP client with automatic JWT authentication via Ktor Auth plugin.
         *
         * The shared [createPosHttpClient] owns bearer attachment + silent refresh on 401
         * (rotating tokens in [TokenStore]); a genuinely failed refresh fires
         * [onUnauthorized] → [TerminalColdResetBus.request] so the app returns to
         * device pairing. Runtime 403/409 terminal-auth failures are surfaced by
         * the launch gate and login flow; a server-pushed SSE revocation triggers
         * the same cold reset via [TerminalLifecycleHandler].
         *
         * Per-platform engine tuning comes from [platformHttpClientConfig]; SSE /
         * ContentEncoding / HttpTimeout are composeApp-only and installed via
         * `extraConfig` (they are meaningless or harmful on a browser engine). `extraConfig`
         * also installs [org.example.project.core.network.ServerUrlGuard] — till-only, because
         * adminApp is served same-origin by pos-server and must keep working over http on shops
         * that have not re-run the installer; the guard must never move into the shared client.
         */
        single<HttpClient> {
            val coldResetBus: TerminalColdResetBus = get()
            createPosHttpClient(
                engine = createPlatformEngine(platformHttpClientConfig()),
                tokenStore = get(),
                // A dead employee session is not a dead pairing: it takes the till back to the
                // login screen, not to the pairing screen (E2E-2026-09-19).
                onUnauthorized = { coldResetBus.request(TerminalResetKind.SESSION) },
                // A station's device-token exchange refused with 409/403 IS the pairing being
                // revoked or deactivated, so that one still unpairs.
                onDeviceRejected = { coldResetBus.request(TerminalResetKind.COLD_DEVICE_REJECTED) },
                // A silent refresh brings a fresh capability set. PermissionManager caches the
                // resolved set, so without this push it would serve the login-time answer for the
                // whole session and a group change would wait for logout. Resolved lazily inside the
                // lambda so the HTTP client does not depend on PermissionManager at construction.
                onTokensRefreshed = { get<PermissionManager>().setReportedCapabilities(get<TokenStore>().capabilities) },
                baseUrlResolver = get<LocalDatabaseManager>()::getApiBaseUrl,
                logger = KermitKtorLogger(get<co.touchlab.kermit.Logger>().withTag("KtorHttpClient")),
                logLevel = httpLogLevel(logBodies = BuildKonfig.HTTP_LOG_BODIES),
            ) {
                installTillClientPlugins()
            }
        }

        single<HttpCall> {
            LocalDbHttpCall(get(), get())
        }

        /**
         * Notification connection for real-time communication.
         *
         * Provides Server-Sent Events connectivity with automatic reconnection,
         * JWT authentication, Last-Event-ID replay, and reconcile-on-connect for
         * real-time updates. Bound as [NotificationConnection] so
         * [NotificationViewModel] is transport-agnostic.
         */
        single { ReconnectPolicy() }
        // Only the browser client reloads on a server upgrade; native clients ship with their installer.
        single {
            ServerBuildWatcher(
                bundleVersion = BuildKonfig.APP_VERSION,
                enabled = isBrowserClient,
                reloadGuard = ReloadLoopGuard(PageReloaderStampStore, { Clock.System.now().toEpochMilliseconds() }),
            )
        }
        single {
            NotificationReconciler(
                syncService = get(),
                changeFeedApiClient = get(),
                localDatabaseManager = get(),
                approvalApiClient = get(),
                billingApiClient = get(),
                permissionManager = get(),
                rotationApiClient = get(),
            )
        }
        // One instance shared by the notification stream (writer) and the dashboard (reader).
        single {
            org.example.project.features.rotation
                .RotationNextStore()
        }
        single<NotificationConnection> {
            SseNotificationConnection(
                client = get(),
                tokenStore = get(),
                authApiClient = get(),
                localDb = get(),
                reconciler = get(),
                reconnectPolicy = get(),
            )
        }

        // ===== API Client Registrations =====

        /**
         * Product Category API client for managing product categories.
         */
        singleOf(::KtorProductCategoryApiClient) bind ProductCategoryApiClient::class

        /**
         * Product Extras API client for managing product extras (add-ons, modifications).
         */
        singleOf(::KtorProductExtrasApiClient) bind ProductExtrasApiClient::class

        /**
         * Product Extras Assignment API client for managing product-extras assignment relationships.
         */
        singleOf(::KtorProductExtrasAssignmentApiClient) bind ProductExtrasAssignmentApiClient::class

        /**
         * Choice Group API clients for managing choice groups and product attachments.
         */
        singleOf(::KtorChoiceGroupApiClient) bind ChoiceGroupApiClient::class
        singleOf(::KtorProductChoiceGroupApiClient) bind ProductChoiceGroupApiClient::class

        /**
         * Pfand (deposit) type catalog — read-only list.
         */
        singleOf(::KtorPfandTypeApiClient) bind PfandTypeApiClient::class

        /**
         * Product API client for managing products.
         */
        singleOf(::KtorProductApiClient) bind ProductApiClient::class

        /**
         * Restaurant Table API client for managing tables and floor plan positions.
         */
        singleOf(::KtorRTableApiClient) bind RTableApiClient::class

        /**
         * Billing Request API client for recovering missed billing requests on reconnect.
         */
        singleOf(::KtorBillingRequestApiClient) bind BillingRequestApiClient::class

        /**
         * Room Element API client for managing floor plan structural elements.
         */
        singleOf(::KtorRoomElementApiClient) bind RoomElementApiClient::class

        /**
         * Order API client for managing orders and order operations.
         */
        singleOf(::KtorOrderApiClient) bind OrderApiClient::class
        singleOf(::KtorRotationApiClient) bind RotationApiClient::class

        /**
         * Order Item API client for managing order items (cancel, transfer, retrieval).
         */
        singleOf(::KtorOrderItemApiClient) bind OrderItemApiClient::class

/**
         * Employee API client for managing employees, authentication, and permissions.
         */
        singleOf(::KtorEmployeeApiClient) bind EmployeeApiClient::class

        /**
         * Payment Type API client for managing payment types and synchronization.
         */
        singleOf(::KtorPaymentTypeApiClient) bind PaymentTypeApiClient::class

        /**
         * VAT API client for managing VAT rates with German tax compliance.
         */
        singleOf(::KtorVatApiClient) bind VatApiClient::class

        /**
         * Bill API client for creating and managing bills.
         */
        singleOf(::KtorBillApiClient) bind BillApiClient::class

        /**
         * Statistics API client for fetching business intelligence and analytics data.
         */
        singleOf(::KtorStatisticsApiClient) bind StatisticsApiClient::class

        /**
         * ZBon API client for fetching daily closing reports.
         */
        singleOf(::KtorZBonApiClient) bind ZBonApiClient::class

        /**
         * Kassenbuch (cash book) API client for managing DATEV-compliant cash transactions.
         */
        singleOf(::KtorKassenbuchApiClient) bind KassenbuchApiClient::class

        /**
         * Cash book API client: the books themselves (Ladenkasse/Tresor), the Geldtransit between
         * them, and the Kassensturz close of a non-fiscal book.
         */
        singleOf(::KtorCashBookApiClient) bind CashBookApiClient::class

        /**
         * Changelog API client for paginated release notes.
         */
        singleOf(::KtorChangelogApiClient) bind ChangelogApiClient::class

        /**
         * DSFinV-K API client for German fiscal compliance exports.
         *
         * Supports DSFinV-K ZIP export, validation, and statistics retrieval
         * for Kassennachschau (fiscal audit) compliance.
         */
        singleOf(::KtorDsfinvkApiClient) bind DsfinvkApiClient::class

        /**
         * Customer API client for managing customer information, search, and CRUD operations.
         */
        singleOf(::KtorCustomerApiClient) bind CustomerApiClient::class

        /**
         * Customer Address API client for managing customer delivery and billing addresses.
         */
        singleOf(::KtorCustomerAddressApiClient) bind CustomerAddressApiClient::class

        /**
         * Shop Info API client for managing shop information.
         */
        singleOf(::KtorShopInfoApiClient) bind ShopInfoApiClient::class

        /**
         * Shop Configuration API client for managing shop settings (voucher mode, tip mode, etc.).
         */
        singleOf(::KtorShopConfigurationApiClient) bind ShopConfigurationApiClient::class

        /**
         * Ecr Configuration API client (voucher, VAT mapping, print settings, order and billing rules).
         */
        singleOf(::KtorEcrConfigurationApiClient) bind EcrConfigurationApiClient::class

        /**
         * Fiscal Configuration API client (Beleg age, cash-book transfer target, Kassenmeldung).
         */
        singleOf(::KtorFiscalConfigurationApiClient) bind FiscalConfigurationApiClient::class

        /**
         * Voucher API client for creating and managing gift vouchers.
         */
        singleOf(::KtorVoucherApiClient) bind VoucherApiClient::class

        /**
         * Promotion API client for managing promotional vouchers.
         */
        singleOf(::KtorPromotionApiClient) bind PromotionApiClient::class

        /**
         * Printer Location API client for managing printer locations and category assignments.
         */
        singleOf(::KtorPrinterLocationApiClient) bind PrinterLocationApiClient::class

        /**
         * Printer Discovery API client for IPP/CUPS printer detection and status.
         */
        singleOf(::KtorPrinterDiscoveryApiClient) bind PrinterDiscoveryApiClient::class

        /**
         * Print Job History API client for querying print job audit trail.
         */
        singleOf(::KtorPrintJobHistoryApiClient) bind PrintJobHistoryApiClient::class

        /**
         * PDF Management API client for uploading, managing, and linking PDF documents.
         *
         * Supports financial metadata tracking, document linking (invoices to bank statements/kassenbuch),
         * and full-text search with multi-parameter filtering.
         */
        singleOf(::KtorDocumentManagementApiClient) bind DocumentManagementApiClient::class

        /**
         * Outgoing invoices: create from a settled bill, issue, cancel, and fetch the archived PDF.
         */
        singleOf(::KtorSalesInvoiceApiClient) bind SalesInvoiceApiClient::class

        /**
         * TSE Settings API client for TSE configuration and admin operations.
         *
         * Communicates with the main POS server's TSE admin endpoints.
         * Server proxies requests to TSE Connector Server for status/info/operations.
         */
        single<TseSettingsApiClient> {
            KtorTseSettingsApiClient(get())
        }

        /**
         * TSE Queue API client for signing queue monitoring and management.
         *
         * Communicates with the main POS server's TSE queue management endpoints.
         * Used by TSE Settings UI for queue status, entries, and service control.
         */
        single<TseQueueApiClient> {
            KtorTseQueueApiClient(get())
        }

        /**
         * Terminal API client for terminal registration and management.
         *
         * Used to register this device as a terminal with the server for
         * multi-client DSFinV-K compliance tracking.
         */
        singleOf(::KtorTerminalApiClient) bind TerminalApiClient::class

        /**
         * Backup API client for database backup and restore operations.
         *
         * Used for creating PostgreSQL custom format backups (.dump files)
         * and restoring database from backup files.
         */
        singleOf(::KtorBackupApiClient) bind BackupApiClient::class

        /**
         * Setup API client for initial system setup operations.
         *
         * Used for checking system initialization status and performing
         * atomic complete setup from the UI wizard. Unauthenticated endpoints.
         */
        singleOf(::KtorSetupApiClient) bind SetupApiClient::class

        /**
         * Work Schedule API client for managing employee weekly schedules.
         */
        singleOf(::KtorWorkScheduleApiClient) bind WorkScheduleApiClient::class

        /**
         * Time Tracking API client for clock-in/out, approvals, cost centers, and DATEV export.
         */
        singleOf(::KtorTimeTrackingApiClient) bind TimeTrackingApiClient::class

        /**
         * Scheduled Job API client for managing user-configurable cron-style scheduled tasks.
         */
        singleOf(::KtorScheduledJobApiClient) bind ScheduledJobApiClient::class
        singleOf(::KtorUpdateApiClient) bind UpdateApiClient::class
        singleOf(::KtorAppUpdateApiClient) bind AppUpdateApiClient::class
        singleOf(::KtorIssueReportApiClient) bind IssueReportApiClient::class

        /**
         * Opening Hours API client for managing weekly schedules and date overrides.
         */
        singleOf(::KtorOpeningHoursApiClient) bind OpeningHoursApiClient::class

        /**
         * Location Type API client for managing location types (dine-in, takeaway, virtual, etc.).
         */
        singleOf(::KtorLocationTypeApiClient) bind LocationTypeApiClient::class

        /**
         * Reservation API client for managing reservations and reservation rules.
         */
        singleOf(::KtorReservationApiClient) bind ReservationApiClient::class

        /**
         * Audit Log API client for paginated entry logs and transaction audit logs.
         *
         * Used by managers/admins to view operation history and financial
         * transaction audit trails with filtering and pagination.
         */
        singleOf(::KtorAuditLogApiClient) bind AuditLogApiClient::class

        /**
         * Session Policy API client for the admin-only daily re-login policy
         * and the revoke-all-sessions (panic) action.
         */
        singleOf(::KtorSessionPolicyApiClient) bind SessionPolicyApiClient::class

        /**
         * Change Feed API client for polling the server's sequential change log.
         */
        singleOf(::KtorChangeFeedApiClient) bind ChangeFeedApiClient::class

        /**
         * Reports errors shown to the user into the server's client-error log.
         *
         * Its own scope, not a screen's: a report must outlive the screen that raised the error —
         * an error dialog is very often followed by that screen being torn down.
         */
        single<ClientErrorReporter> {
            val local = get<LocalDatabaseManager>()
            DefaultClientErrorReporter(
                httpCall = get(),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                app = "POS_TILL",
                appVersion = BuildKonfig.APP_VERSION,
                employeeNumberProvider = { local.getEmployeeNumber() },
                terminalIdProvider = { local.getTerminalId() },
            )
        }
    }
