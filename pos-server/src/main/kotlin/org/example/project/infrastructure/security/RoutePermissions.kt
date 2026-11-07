package org.example.project.infrastructure.security

import io.ktor.http.HttpMethod
import org.example.project.infrastructure.security.AccessLevel.*
import org.example.project.infrastructure.security.rules.AccountingRules
import org.example.project.infrastructure.security.rules.AdminRules
import org.example.project.infrastructure.security.rules.AuditRules
import org.example.project.infrastructure.security.rules.AuthRules
import org.example.project.infrastructure.security.rules.BackupRules
import org.example.project.infrastructure.security.rules.BillRules
import org.example.project.infrastructure.security.rules.BookkeepingStartRules
import org.example.project.infrastructure.security.rules.CardSettlementRules
import org.example.project.infrastructure.security.rules.CardTerminalRules
import org.example.project.infrastructure.security.rules.CashBookRules
import org.example.project.infrastructure.security.rules.CatalogRules
import org.example.project.infrastructure.security.rules.ChangeFeedRules
import org.example.project.infrastructure.security.rules.ChangelogRules
import org.example.project.infrastructure.security.rules.ConfigRules
import org.example.project.infrastructure.security.rules.ContractRules
import org.example.project.infrastructure.security.rules.CreditorRules
import org.example.project.infrastructure.security.rules.CustomExportRules
import org.example.project.infrastructure.security.rules.CustomerRules
import org.example.project.infrastructure.security.rules.DebtorRules
import org.example.project.infrastructure.security.rules.DocumentRules
import org.example.project.infrastructure.security.rules.DsfinvkRules
import org.example.project.infrastructure.security.rules.EmployeeRules
import org.example.project.infrastructure.security.rules.InvoiceRules
import org.example.project.infrastructure.security.rules.IssueReportRules
import org.example.project.infrastructure.security.rules.MaintenanceRules
import org.example.project.infrastructure.security.rules.MonthlyCloseRules
import org.example.project.infrastructure.security.rules.NotificationRules
import org.example.project.infrastructure.security.rules.OrderRules
import org.example.project.infrastructure.security.rules.PartnerRules
import org.example.project.infrastructure.security.rules.PayrollRules
import org.example.project.infrastructure.security.rules.PrinterRules
import org.example.project.infrastructure.security.rules.PromotionRules
import org.example.project.infrastructure.security.rules.ReservationConfigRules
import org.example.project.infrastructure.security.rules.ReservationRules
import org.example.project.infrastructure.security.rules.ScheduledJobRules
import org.example.project.infrastructure.security.rules.SetupRules
import org.example.project.infrastructure.security.rules.ShopInfoRules
import org.example.project.infrastructure.security.rules.StatisticsRules
import org.example.project.infrastructure.security.rules.TableRules
import org.example.project.infrastructure.security.rules.TerminalRules
import org.example.project.infrastructure.security.rules.TimeTrackingRules
import org.example.project.infrastructure.security.rules.TrainingReportRules
import org.example.project.infrastructure.security.rules.TseRules
import org.example.project.infrastructure.security.rules.UpdateRules
import org.example.project.infrastructure.security.rules.VoucherRules
import org.example.project.infrastructure.security.rules.ZBonRules
import org.slf4j.LoggerFactory

/**
 * Centralized route permission registry.
 *
 * Maps every API endpoint to its required [AccessLevel]. The [RouteAuthorizationPlugin]
 * consults this registry on every authenticated request to enforce access rules.
 * Unregistered routes are denied by default.
 *
 * @since 2.0.0
 * @author Generated with Claude Code
 */
object RoutePermissions {
    private val logger = LoggerFactory.getLogger(RoutePermissions::class.java)

    /**
     * Every [RuleModule]. Their union is the complete rule set — the monolithic literal this object
     * used to hold is gone, migrated module by module in Stage 1 Task 6 with
     * `CapabilityEquivalenceTest` proving no verdict changed at any step.
     */
    val modules: List<RuleModule> =
        listOf(
            CashBookRules,
            ConfigRules,
            TerminalRules,
            CardTerminalRules,
            BookkeepingStartRules,
            ShopInfoRules,
            SetupRules,
            AuthRules,
            DocumentRules,
            BillRules,
            PayrollRules,
            TimeTrackingRules,
            EmployeeRules,
            ZBonRules,
            PromotionRules,
            OrderRules,
            TableRules,
            CardSettlementRules,
            InvoiceRules,
            ContractRules,
            DebtorRules,
            CreditorRules,
            PartnerRules,
            CustomExportRules,
            DsfinvkRules,
            MonthlyCloseRules,
            AccountingRules,
            AdminRules,
            BackupRules,
            TseRules,
            ReservationConfigRules,
            ReservationRules,
            CatalogRules,
            ScheduledJobRules,
            UpdateRules,
            IssueReportRules,
            AuditRules,
            PrinterRules,
            ChangelogRules,
            ChangeFeedRules,
            NotificationRules,
            TrainingReportRules,
            StatisticsRules,
            CustomerRules,
            VoucherRules,
            MaintenanceRules,
        )

    private val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        buildMap {
            modules.forEach { module ->
                module.rules.forEach { (key, access) ->
                    // Two modules claiming the same (path, method) would silently take whichever
                    // the fold saw last. Fail at startup instead.
                    require(put(key, access) == null) {
                        "Duplicate rule ${key.second.value} ${key.first} in module ${module.name}"
                    }
                }
            }
        }

    // Split into exact (static paths) and parameterized (paths with {param}) at init
    val exactRules: Map<Pair<String, HttpMethod>, AccessLevel>
    val paramRules: List<ParameterizedRule>

    init {
        val exact = mutableMapOf<Pair<String, HttpMethod>, AccessLevel>()
        val param = mutableListOf<ParameterizedRule>()

        rules.forEach { (key, access) ->
            if (key.first.contains("{")) {
                param.add(ParameterizedRule(key.first, key.second, access))
            } else {
                exact[key] = access
            }
        }

        exactRules = exact
        paramRules = param

        // Validate no duplicates (caught at startup)
        logger.info("Route permissions loaded: ${exact.size} exact rules, ${param.size} parameterized rules")
    }

    /**
     * Find the access level rule for a given path and HTTP method.
     *
     * Performs O(1) exact match first, then falls back to parameterized pattern matching.
     *
     * @param path The request path (e.g., "/api/employees/123")
     * @param method The HTTP method
     * @return The matching [AccessLevel], or null if no rule is registered
     */
    fun findRule(
        path: String,
        method: HttpMethod,
    ): AccessLevel? {
        // O(1) exact match first
        exactRules[path to method]?.let { return it }

        // Fall back to parameterized pattern matching
        return paramRules.firstOrNull { it.matches(path, method) }?.access
    }

    /**
     * Every registered rule, typed — the form the Stage 5c diagnostics walk.
     *
     * [allRules] renders the same table as strings for the raw admin dump. This one keeps the
     * [AccessLevel] so a caller can actually evaluate it against a capability set rather than
     * parsing its own description back out.
     */
    fun registeredRules(): List<Triple<String, HttpMethod, AccessLevel>> = rules.map { (key, access) -> Triple(key.first, key.second, access) }

    /**
     * Returns all rules for the admin endpoint.
     *
     * @return List of maps with path, method, and access level description
     */
    fun allRules(): List<Map<String, String>> =
        rules.map { (key, access) ->
            mapOf(
                "path" to key.first,
                "method" to key.second.value,
                "access" to access.description,
            )
        }
}

/**
 * Represents a parameterized route rule with path segments that may contain
 * `{param}` placeholders for dynamic matching.
 *
 * @property pattern The route pattern (e.g., "/api/employees/{id}")
 * @property method The HTTP method
 * @property access The required access level
 */
data class ParameterizedRule(
    val pattern: String,
    val method: HttpMethod,
    val access: AccessLevel,
) {
    private val segments = pattern.split("/")

    /**
     * Check if a concrete path matches this parameterized pattern.
     *
     * @param path The concrete request path (e.g., "/api/employees/123")
     * @param httpMethod The HTTP method
     * @return true if the path matches this pattern
     */
    fun matches(
        path: String,
        httpMethod: HttpMethod,
    ): Boolean {
        if (httpMethod != method) return false
        val pathSegments = path.split("/")
        if (pathSegments.size != segments.size) return false
        return segments.zip(pathSegments).all { (pattern, actual) ->
            pattern.startsWith("{") && pattern.endsWith("}") || pattern == actual
        }
    }
}
