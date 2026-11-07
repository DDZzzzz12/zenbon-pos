package org.example.project.core.utils.enumStrings

import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.EnumKeys

/**
 * The on-screen name of a TSE log entry type as the server sends it (the Swissbit
 * `WormEntryType` name). An unknown value (including `UNKNOWN`) is returned unchanged.
 */
fun tseLogEntryTypeLabel(
    raw: String,
    language: Language,
): String =
    when (raw) {
        "WORM_ENTRY_TYPE_TRANSACTION" -> getString(EnumKeys.tseLogEntryTransaction, language)
        "WORM_ENTRY_TYPE_SYSTEM_LOG_MESSAGE" -> getString(EnumKeys.tseLogEntrySystem, language)
        "WORM_ENTRY_TYPE_SE_AUDIT_LOG_MESSAGE" -> getString(EnumKeys.tseLogEntryAudit, language)
        else -> raw
    }
