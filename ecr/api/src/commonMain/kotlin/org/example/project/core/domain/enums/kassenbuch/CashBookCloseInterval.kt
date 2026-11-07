package org.example.project.core.domain.enums.kassenbuch

import kotlinx.serialization.Serializable

/**
 * How often a non-fiscal cash book is expected to be closed.
 *
 * A **hint only** — never enforced. It sets the default `periodEnd` in the close dialog and
 * makes "overdue" computable for a status badge. Closing early must always remain possible:
 * an unclosed period blocks Stammdaten edits, so forcing calendar alignment would trap an
 * operator who needs to edit shop data mid-period.
 *
 * The fiscal book never uses this — it is closed by the Z-Bon.
 */
@Serializable
enum class CashBookCloseInterval {
    WEEKLY,
    MONTHLY,
    QUARTERLY,
    MANUAL,
}
