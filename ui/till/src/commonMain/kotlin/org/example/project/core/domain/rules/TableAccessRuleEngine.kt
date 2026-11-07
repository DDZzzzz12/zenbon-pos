package org.example.project.core.domain.rules

import org.example.project.core.auth.PermissionManager
import org.example.project.core.auth.SettingManager
import org.example.project.core.auth.TokenStore
import org.example.project.core.auth.isStation
import org.example.project.core.data.models.RTable
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.RulesKeys

class TableAccessRuleEngine(
    private val settingManager: SettingManager,
    private val permissionManager: PermissionManager,
    private val tokenStore: TokenStore,
) {
    sealed class AccessResult {
        data object Allowed : AccessResult()

        /**
         * The table may not be opened. [reason] is the sentence to show the operator; null for a
         * denial that stays silent (training gates, a principal without TABLE_WRITE).
         */
        data class Denied(
            val reason: StringKey? = null,
        ) : AccessResult()

        data object RequiresEmployeeSelection : AccessResult()
    }

    fun checkTableAccess(table: RTable): AccessResult {
        val tableStatus =
            try {
                TableStatus.valueOf(table.tableStatus)
            } catch (_: IllegalArgumentException) {
                TableStatus.FREE
            }

        val isTraining = permissionManager.isTrainingMode.value

        // Training mode gates
        if (table.isTraining && !isTraining) return AccessResult.Denied()
        if (!table.isTraining && isTraining && tableStatus == TableStatus.OCCUPIED) return AccessResult.Denied()

        // An order station never owns a table session (spec 2026-09-14 §5.1): it opens a table only
        // through rotation — the server makes the rotated employee the owner — and never a takeaway or
        // virtual one. This applies whenever the table has no active session, not only a FREE one: a
        // RESERVED table, or one merely marked OCCUPIED with its session already gone, is just as
        // ownerless, and the server enforces the same rule on submit either way. A table WITH a session
        // skips this branch entirely, so a station books onto any open session, takeaway included.
        if (tokenStore.isStation && table.currentSessionId == null) {
            if (table.isTakeaway || table.isVirtual) return AccessResult.Denied(RulesKeys.stationCannotOpenVirtualTable)
            val roundRobinEnabled = settingManager.roundRobinAssignment.value
            if (!roundRobinEnabled) return AccessResult.Denied(RulesKeys.stationTableRequiresRotation)
            return AccessResult.Allowed
        }

        // FREE table logic (person path only — a station is handled above)
        if (tableStatus == TableStatus.FREE) {
            val roundRobinEnabled = settingManager.roundRobinAssignment.value
            if (roundRobinEnabled) {
                // Server picks the employee via round-robin; no client prompt.
                return AccessResult.Allowed
            }
            val employeeSelectionEnabled = settingManager.employeeSelectionOnFreeTable.value
            if (employeeSelectionEnabled) {
                return AccessResult.RequiresEmployeeSelection
            }
            // Opening a free table starts a session: TABLE_WRITE, the capability the server asks for.
            if (!permissionManager.accessPolicy.value.allows(Capability.TABLE_WRITE)) return AccessResult.Denied()
        }

        return AccessResult.Allowed
    }

    /**
     * Whether the current principal can select themselves in the employee selection dialog.
     * A station has no self to pick — it is the client mirror of the `station` claim, not a role.
     */
    fun canSelectSelf(): Boolean = !tokenStore.isStation
}
