/**
 * Localized label for a kassenbuch line kind.
 *
 * Shared read-only helper used by [LineKindBadge], the entry-detail sections,
 * and composeApp's DATEV mapping / create-entry panels.
 */
package org.example.project.ui.components.kassenbuch

import androidx.compose.runtime.Composable
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.core.presentation.strings.keys.KassenbuchKeys
import org.example.project.core.presentation.strings.str

@Composable
fun kindLabel(kind: KassenbuchLineKind): String =
    when (kind) {
        KassenbuchLineKind.STANDARD -> str(KassenbuchKeys.lineKindStandard)
        KassenbuchLineKind.PFAND -> str(KassenbuchKeys.lineKindPfand)
        KassenbuchLineKind.GUTSCHEIN -> str(KassenbuchKeys.lineKindGutschein)
        KassenbuchLineKind.NON_CASH_PAYMENT -> str(KassenbuchKeys.lineKindNonCash)
        KassenbuchLineKind.RECEIVABLE_SETTLEMENT -> str(KassenbuchKeys.lineKindReceivableSettlement)
        KassenbuchLineKind.EMPLOYEE_TIP -> str(KassenbuchKeys.lineKindEmployeeTip)
    }
