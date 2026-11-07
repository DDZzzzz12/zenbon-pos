package org.example.project.features.main.presentation.navigation

import kotlinx.serialization.Serializable

/**
 * Nested navigation destinations for the main POS application screens.
 *
 * Defines the internal navigation structure within the authenticated main application.
 * Uses type-safe serializable routes for all main app sections.
 */

/**
 * Reports and analytics destination.
 * Sales reports, financial summaries, and business analytics.
 */
@Serializable
object Reports

/**
 * Settings and configuration destination.
 * App settings, preferences, and system configuration.
 */
@Serializable
object AppSettings

/**
 * Cashier self-service profile destination.
 * Shows the signed-in employee's own profile and credential change.
 */
@Serializable
object MyProfile

/**
 * Dashboard/Overview destination.
 * Main overview with key metrics and quick actions.
 * Contains nested navigation for table-related operations.
 */
@Serializable
object Dashboard

/**
 * Financial Documents destination.
 * Integrated view of Kassenbuch entries, bank statements, and invoices with adaptive layouts.
 * Features dual-pane layout on tablets/desktop and tab-based navigation on phones.
 * Includes enhanced document linking workflow for accounting operations.
 * Requires MANAGER role or higher.
 */
@Serializable
object FinancialDocuments

/**
 * Audit Log destination.
 * Paginated view of entry logs and transaction audit logs with filtering.
 * Requires MANAGER role or higher.
 */
@Serializable
object AuditLog

/**
 * Reservation management destination.
 * Create, view, and manage table reservations.
 * Visible to all authenticated users.
 */
@Serializable
object MainReservations

/**
 * Notification Center destination.
 * View and manage notifications, connection status, billing requests.
 */
@Serializable
object MainNotifications
