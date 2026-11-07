package org.example.project.di

import io.ktor.client.HttpClient
import org.example.project.core.auth.SettingManagerDeviceMirror
import org.example.project.core.data.cache.TableStatusCacheWriter
import org.example.project.core.data.database.ShopDataManager
import org.example.project.core.network.forgetCachedBearer
import org.example.project.core.presentation.components.notification.GlobalOverlayViewModel
import org.example.project.core.presentation.settings.DeviceSettingsMirror
import org.example.project.core.presentation.viewmodel.FileBrowserViewModel
import org.example.project.features.appupdate.presentation.viewmodels.AppUpdateViewModel
import org.example.project.features.auditlog.presentation.viewmodel.AuditLogViewModel
import org.example.project.features.auth.presentation.viewmodels.ApprovalRequestViewModel
import org.example.project.features.auth.presentation.viewmodels.LoginViewModel
import org.example.project.features.auth.presentation.viewmodels.TemporarySessionManager
import org.example.project.features.changelog.presentation.viewmodel.ChangelogViewModel
import org.example.project.features.dashboard.presentation.viewmodel.*
import org.example.project.features.dashboard.presentation.viewmodel.PosTableDetailViewModel
import org.example.project.features.employees.presentation.viewmodel.CashierProfileViewModel
import org.example.project.features.financialdocuments.presentation.state.FinancialDocumentsEventBus
import org.example.project.features.financialdocuments.presentation.viewmodel.DocumentUploadViewModel
import org.example.project.features.financialdocuments.presentation.viewmodel.KassenbuchViewModel
import org.example.project.features.launch.presentation.TerminalColdResetBus
import org.example.project.features.launch.presentation.TerminalLifecycleHandler
import org.example.project.features.launch.presentation.viewmodels.LaunchCoordinator
import org.example.project.features.main.presentation.viewmodels.MainViewModel
import org.example.project.features.notifications.presentation.viewmodel.NotificationViewModel
import org.example.project.features.reports.presentation.viewmodel.ReportsViewModel
import org.example.project.features.reports.presentation.viewmodel.ZBonViewModel
import org.example.project.features.reservation.presentation.viewmodels.ReservationFormViewModel
import org.example.project.features.reservation.presentation.viewmodels.ReservationListViewModel
import org.example.project.features.reservation.presentation.viewmodels.ReservationRulesViewModel
import org.example.project.features.reservation.presentation.viewmodels.TableAssignmentViewModel
import org.example.project.features.scheduler.presentation.viewmodels.ScheduledJobsViewModel
import org.example.project.features.settings.presentation.viewmodel.GeneralSettingsViewModel
import org.example.project.features.settings.presentation.viewmodel.LocationTypeViewModel
import org.example.project.features.settings.presentation.viewmodel.OpeningHoursViewModel
import org.example.project.features.settings.presentation.viewmodel.PrinterSettingsViewModel
import org.example.project.features.settings.presentation.viewmodel.RulesViewModel
import org.example.project.features.settings.presentation.viewmodel.SecuritySessionsViewModel
import org.example.project.features.settings.presentation.viewmodel.SettingsViewModel
import org.example.project.features.settings.presentation.viewmodel.TseSettingsViewModel
import org.example.project.features.terminal.presentation.viewmodels.DevicePairingViewModel
import org.example.project.features.voucher.presentation.viewmodel.VoucherCreationViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * ViewModel dependency injection module.
 *
 * Provides cross-platform ViewModel instances using Koin's ViewModel support.
 * This ensures proper lifecycle management across Android, Desktop, and iOS platforms.
 *
 * Benefits of Koin ViewModels in KMP:
 * - Cross-platform lifecycle management
 * - Automatic scoping and cleanup
 * - Consistent behavior across all platforms
 * - Integration with Compose's ViewModel APIs
 */
val viewModelModule =
    module {

        single { FinancialDocumentsEventBus() }

        /**
         * Login ViewModel for authentication flow.
         *
         * Manages login screen state, form validation, and authentication logic.
         * Scoped to the login screen lifecycle with automatic cleanup.
         *
         * Dependencies:
         * - LoginUseCase: Use case for login/logout operations
         * - LocalDatabaseManager: Local storage for preferences
         * - PermissionManager: Permission and role management
         * - TokenStore: JWT token persistence
         * - SetupApiClient: Server setup/health checks
         * - PlatformNotificationService: foreground notification service
         */
        viewModel { LoginViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }

        /**
         * Temporary Session Manager for privilege escalation.
         *
         * Manages temporary manager sessions for BASIC_EMPLOYEE users with:
         * - Countdown timer (updates every second)
         * - Automatic session extension when activity detected
         * - Server synchronization every 30 seconds
         * - Clean session creation and termination
         *
         * Dependencies:
         * - TemporarySessionApiClient: API client for session endpoints
         * - PermissionManager: Role and permission management with temporary role support
         * - AuthApiClient: reads the ELEVATED principal's capabilities from /api/auth/me during a
         *   session (getTokenClaims elevates, so /me answers as that principal)
         */
        viewModel { TemporarySessionManager(get(), get(), get()) }

        /**
         * Approval Request ViewModel for manager-side approval workflow.
         *
         * Manages pending approval requests, handles approve/deny actions,
         * and displays the approval dialog on incoming WebSocket events.
         *
         * Dependencies:
         * - ApprovalRequestApiClient: API client for approval request endpoints
         * - PermissionManager: who is signed in, so a sign-out drops the previous person's requests
         */
        viewModel { ApprovalRequestViewModel(get(), get()) }

        /**
         * Main ViewModel for application state management.
         *
         * Manages main application flow and navigation between different application states.
         * Note: Desktop initialization is handled separately through DesktopInitializationViewModel.
         */
        viewModel {
            MainViewModel(
                preferenceSyncService = get(),
                syncService = get(),
                terminalRegistrationService = get(),
            )
        }

        /**
         * Dashboard ViewModel for table display and management.
         *
         * Manages dashboard screen state, loads tables from local database,
         * and handles table type selection for grid display.
         *
         * Dependencies:
         * - RTableDataProvider: Data provider for table reads
         * - LocationTypeApiClient: API client for fetching location types
         */
        viewModel { TableGridViewModel(get(), get(), get()) }

        /**
         * Table Operations ViewModel for transfer and deletion.
         *
         * Manages table transfer, deletion, and customer assignment operations
         * separately from grid display logic. Handles API calls and error states.
         *
         * Dependencies:
         * - RTableApiClient: API client for table operations
         * - RTableDataManager: Local database operations for tables
         * - EmployeeApiClient: API client for employee operations
         * - CustomerApiClient: API client for customer operations
         * - CustomerAddressApiClient: API client for customer address operations
         */
        viewModel { TableOperationsViewModel(get(), get(), get(), get(), get(), get()) }

        /**
         * Floor Plan ViewModel for the read-only floor plan view.
         *
         * Manages floor plan state including tables, room elements, viewport
         * transformations (zoom/pan) and combine-mode table connections. Layout
         * authoring lives in the web admin panel, not on the till.
         *
         * Dependencies:
         * - RTableDataManager: Local database operations for tables
         * - RoomElementDataManager: Local database operations for room elements
         */
        viewModel { FloorPlanViewModel(get(), get(), get(), get()) }

        /**
         * Table Order ViewModel for order creation and management.
         *
         * Manages order screen state for a specific table, including order items,
         * keyboard input for product codes, category/product selection, and order total calculations.
         *
         * Dependencies:
         * - RTableDataProvider: Data provider for table reads
         * - ProductDataProvider: Data provider for product reads
         * - ProductCategoryDataProvider: Data provider for category reads
         * - ProductExtrasAssignmentDataProvider: Data provider for product-extras assignments
         * - ProductExtraDataProvider: Data provider for product extras
         * - ClientOrderService: Service for order submission
         * - RTableApiClient: API client for table operations
         */
        viewModel { TableOrderViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }

        /**
         * Notification ViewModel for real-time notifications.
         *
         * Connects to WebSocket and manages real-time notifications from the server,
         * including table status updates, session events, and order updates.
         * Provides UI state for notification panel and unread count badge.
         *
         * Dependencies:
         * - NotificationConnection: WebSocket or in-process transport
         * - LanguageManager: Multi-language support
         * - SyncService: Entity synchronization on data change events
         */
        single<org.example.project.core.presentation.components.notification.OverlayEventBus> {
            org.example.project.core.presentation.components.notification
                .OverlayEventBusImpl(get())
        }
        viewModel {
            NotificationViewModel(
                get(),
                get(),
                get(),
                getOrNull<TableStatusCacheWriter>(),
                get(),
                get(),
                get(),
                get(),
                permissionManager = get(),
            )
        }
        viewModel { GlobalOverlayViewModel(get()) }

        /**
         * Table Billing ViewModel for creating bills with quantity-aware item selection.
         *
         * Manages billing flow including:
         * - Loading table session and open order items
         * - Quantity selection for partial billing
         * - Multiple payment type support
         * - Voucher validation and redemption
         * - Tip entry
         * - Bill creation via API
         *
         * Dependencies:
         * - RTableDataManager: Get table info and session ID
         * - ClientOrderService: Get order items for table session
         * - PaymentTypeDataManager: Get active payment types
         * - BillApiClient: Create bill on server
         * - VoucherApiClient: Validate vouchers
         * - SettingManager: Load voucher configuration
         * - LanguageManager: Resolve success messages in the operator's language
         */
        viewModel { TableBillingViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }

        /**
         * BillReceiptViewModel for bill receipt operations.
         *
         * Dependencies:
         * - BillApiClient: Bill operations (delete, print)
         * - DocumentManagementApiClient: Document generation operations
         * - PaymentTypeDataProvider: Active payment types for the Zahlartwechsel dialog
         * - VoucherApiClient: Validate vouchers entered in that dialog
         * - SettingManager: Load voucher configuration
         */
        viewModel { BillReceiptViewModel(get(), get(), get(), get(), get()) }

        /**
         * PdfViewerViewModel for IPP+PDF printer operations within DocumentViewerPanel.
         *
         * Manages loading of IPP+PDF printer locations and sending PDF documents
         * to printers via IPP protocol. Used internally by DocumentViewerPanel.
         *
         * Dependencies:
         * - PrinterLocationApiClient: Fetching printer location configurations
         * - PrinterDiscoveryApiClient: Sending PDF to IPP printers
         */
        viewModel { PdfViewerViewModel(get(), get()) }

        viewModel { CashierProfileViewModel(get(), get(), get(), get(), get()) }

        /**
         * Table Order History ViewModel for unified table management.
         *
         * Manages three-tab interface:
         * - Table details with item management (Details tab)
         * - Order history for table session (Orders tab)
         * - Bills for table session (Bills tab)
         *
         * Includes multi-select functionality and bulk operations (cancel/transfer).
         *
         * Dependencies:
         * - OrderApiClient: API client for fetching order data
         * - BillApiClient: API client for fetching bill data
         * - RTableDataManager: Data manager for table operations
         * - ClientOrderService: Service for order item operations (get, cancel, transfer)
         */
        viewModel { TableOrderHistoryViewModel(get(), get(), get(), get(), get()) }

        /**
         * Reports ViewModel for cashier and shift supervisor statistics views.
         *
         * Manages role-based statistics display and panel visibility coordination:
         * - Cashier: Personal statistics for today only
         * - Shift Supervisor: Team performance for today only
         * - Manager+: Full dashboard with employee selection for filtering
         * - Panel visibility: Coordinates Z-Bon and Kassenbuch panel display
         *
         * Dependencies:
         * - StatisticsApiClient: API client for fetching statistics data
         * - PermissionManager: Manager for role-based access control
         * - ZBonApiClient: API client for fetching Z-Bon start time
         * - EmployeeApiClient: API client for loading employee list (manager filtering)
         */
        viewModel { ReportsViewModel(get(), get(), get(), get(), get()) }

        /**
         * Z-Bon ViewModel for daily closing report management.
         *
         * Manages Z-Bon creation, history viewing, and date range filtering
         * independently from main reports screen.
         *
         * Dependencies:
         * - ZBonApiClient: API client for Z-Bon operations
         * - LanguageManager: Resolve success messages in the operator's language
         */
        viewModel { ZBonViewModel(get(), get(), get(), get()) }

        /**
         * Kassenbuch ViewModel for cash book management.
         *
         * Manages kassenbuch entry creation, history viewing, and dialog states
         * independently from main reports screen. Supports PDF attachment during
         * entry creation via upload + link orchestration.
         *
         * Dependencies:
         * - KassenbuchApiClient: API client for kassenbuch operations
         * - ZBonApiClient: API client for fetching date range (last Z-Bon time)
         * - DocumentManagementApiClient: API client for document upload and linking
         */
        viewModel { KassenbuchViewModel(get(), get(), get(), get(), get()) }

        /**
         * Settings ViewModel for application settings management.
         *
         * Manages general settings including language, currency, date/time formats.
         * Handles loading and saving settings with auto-save functionality.
         *
         * Dependencies: None (uses local storage only for now)
         */
        viewModel { SettingsViewModel(get(), get()) }

        viewModel { GeneralSettingsViewModel(get(), get()) }

        /**
         * Security / Sessions ViewModel for the admin-only daily re-login policy
         * and revoke-all-sessions (panic) action.
         *
         * Dependencies:
         * - SessionPolicyApiClient: API client for session policy + revoke-all
         */
        viewModel { SecuritySessionsViewModel(get()) }

        /**
         * Device settings mirror seam, backing RulesViewModel below.
         * On composeApp it writes straight through to the device's SettingManager; the web admin
         * has no till to mirror to.
         */
        single<DeviceSettingsMirror> { SettingManagerDeviceMirror(get()) }

        /**
         * Rules ViewModel for Order Rules + Billing Rules management.
         *
         * Consolidates employee-selection-on-free-table, round-robin assignment,
         * cross-employee billing, and rotation pool management into one VM.
         *
         * Dependencies:
         * - EcrConfigurationApiClient: API client for order/billing rules
         * - EmployeeApiClient: API client for rotation pool operations
         * - DeviceSettingsMirror: Device-local mirror for offline rule enforcement
         */
        viewModel { RulesViewModel(get(), get(), get()) }

        /**
         * Location Type ViewModel for location type CRUD operations.
         *
         * Dependencies:
         * - LocationTypeApiClient: API client for location type operations
         */
        viewModel { LocationTypeViewModel(get()) }

        /**
         * Printer Settings ViewModel for printer configuration management.
         *
         * Manages printer setup and location configuration including:
         * - Printer hardware setup (USB/Network printers)
         * - Printer location management (Kitchen, Bar, etc.)
         * - Category-to-location mapping for distributed printing
         * - Print job history (audit trail)
         *
         * Dependencies:
         * - PrinterLocationApiClient: API client for printer location operations
         * - ProductCategoryApiClient: API client for product category data
         * - EcrConfigurationApiClient: API client for the ecr-owned shop configuration
         * - PrinterDiscoveryApiClient: API client for IPP printer discovery
         */
        // Last argument is the shop id, not a ShopDataManager: that class is SQLDelight-backed and
        // cannot cross to wasm, so the ViewModel moved to shared-ui taking the id instead. The till
        // still resolves it from its ShopDataManager here.
        viewModel {
            PrinterSettingsViewModel(get(), get(), get(), get(), get(), get<ShopDataManager>().getShopId()!!)
        }

        /**
         * Voucher Creation ViewModel for voucher sales and management.
         *
         * Manages voucher creation in two modes:
         * - Counter sale: voucher sold directly at the counter, paid immediately
         * - Table Session: Adding voucher to existing table order
         *
         * Dependencies:
         * - VoucherApiClient: API client for voucher operations
         * - PaymentTypeDataProvider: Data provider for reading active payment types
         * - BillApiClient: prints the counter sale's receipt from its bill
         * - EcrConfigurationApiClient + shop id: print-settings gate for that print
         *
         * Note: Employee ID is automatically included in JWT token, no need to pass separately
         */
        viewModel { VoucherCreationViewModel(get(), get(), get(), get(), get<ShopDataManager>()::getShopId) }

        /**
         * Document Upload ViewModel for document upload with metadata.
         *
         * The till's cash book cannot create an entry without it: the server requires a
         * documentId on every Kassenbuch entry and auto-Eigenbeleg generation was removed.
         * Communicates with the entry panel via FinancialDocumentsEventBus.
         *
         * Dependencies:
         * - DocumentManagementApiClient: API client for document upload and document types
         * - LanguageManager: Multi-language support for validation messages
         * - FinancialDocumentsEventBus: Event bus for cross-VM communication
         */
        viewModel { DocumentUploadViewModel(get(), get(), get()) }

        /**
         * TSE Settings ViewModel for TSE administration.
         *
         * Manages TSE configuration, status monitoring, and admin operations
         * (self-test, time sync, health check, export), plus signing queue
         * monitoring and management.
         *
         * Dependencies:
         * - TseSettingsApi: API client for TSE configuration endpoints
         * - TseQueueApi: API client for TSE signing queue management
         */
        viewModel { TseSettingsViewModel(get(), get(), get(), get()) }

        /**
         * Device Pairing ViewModel for the new-device pairing screen (Terminal Phase RB1).
         *
         * Dependencies:
         * - TerminalApiClient (shared): public pairDevice endpoint
         * - LocalDatabaseManager: persists the issued device token + terminal identity
         */
        viewModel { DevicePairingViewModel(get(), get()) }

        /**
         * The single async authority for the launch/auth flow — checks setup status, validates
         * the persisted device token, and resolves the destination AppNavigation renders.
         */
        viewModel { LaunchCoordinator(get(), get(), get(), get(), get(), get(), get(), get(), get()) }

        /**
         * Signal bus for terminal-auth failures that require a cold reset.
         *
         * Buffers one request so an early HTTP failure before the app root starts
         * collecting is still handled.
         */
        single { TerminalColdResetBus() }

        /**
         * Terminal lifecycle handler.
         *
         * Listens to server-pushed termination SSEs and performs a cold reset
         * (clear tokens / device registration / user info, then route to pairing).
         *
         * `clearCachedAuth` is `forgetCachedBearer`, the same sweep `KtorAuthApiClient` runs on
         * login and logout and `startupSessionState` runs on the startup WIPE. The provider caches the tokens `loadTokens` returned and reloads them
         * only on a 401, so clearing the TokenStore alone leaves the previous session's JWT riding
         * on every request. The client is resolved once, here, rather than handed to the handler —
         * see its KDoc for why the dependency is a lambda.
         */
        single {
            val httpClient = get<HttpClient>()
            TerminalLifecycleHandler(
                get(),
                get(),
                get(),
                get(),
                get(),
                clearCachedAuth = {
                    httpClient.forgetCachedBearer()
                },
            )
        }

        /**
         * Setup Wizard ViewModel for initial system configuration.
         *
         * Manages the 2-step setup wizard flow (Owner + Shop) and submits
         * atomic setup request to the server.
         *
         * Dependencies:
         * - SetupApiClient: API client for setup status check and initialization
         */

        /**
         * Audit Log ViewModel for paginated audit log display.
         *
         * Manages two-tab audit log screen with entry logs and transaction audit logs,
         * including per-tab filtering and pagination.
         *
         * Dependencies:
         * - AuditLogApiClient: API client for fetching paginated audit logs
         */
        viewModel { AuditLogViewModel(get()) }

        /**
         * Changelog ViewModel for the "What's new" screen.
         *
         * Loads paginated release-note entries from the changelog endpoint.
         *
         * Dependencies:
         * - ChangelogApiClient: API client for fetching the paginated changelog
         */
        viewModel { ChangelogViewModel(get()) }

        /** Android self-update: check, download, verify, install. Inert off Android. */
        viewModel { AppUpdateViewModel(get(), get()) }

        viewModel { OpeningHoursViewModel(get()) }

        viewModel { ReservationListViewModel(get(), get(), get()) }
        viewModel { ReservationFormViewModel(get(), get()) }
        viewModel { ReservationRulesViewModel(get(), get()) }
        viewModel { TableAssignmentViewModel(get()) }

        /**
         * POS Table Detail ViewModel for order item selection and bulk operations.
         *
         * Manages multi-select, cancel, and transfer functionality for the
         * redesigned POS table detail screen.
         *
         * Dependencies:
         * - RTableDataProvider: Data provider for table reads
         * - ClientOrderService: Service for order item operations (get, cancel, transfer)
         * - LanguageManager: Resolve success messages in the operator's language
         */
        viewModel { PosTableDetailViewModel(get(), get(), get(), get(), get(), get<ShopDataManager>()::getShopId) }

        /**
         * File Browser ViewModel for custom file/image browser dialog.
         *
         * Provides directory navigation, file previews, and multi-select
         * for selecting documents and images.
         */
        viewModel { FileBrowserViewModel() }

        /**
         * Scheduled Jobs ViewModel for read-only background-job status and run-now.
         *
         * Dependencies:
         * - ScheduledJobApiClient: API client for scheduled job listing, detail, and run-now
         */
        viewModel { ScheduledJobsViewModel(get()) }

        // Future ViewModels can be added here:
        // viewModel { SalesViewModel() }
        // viewModel { (productId: String) -> ProductDetailViewModel(productId) } // With parameters
    }
