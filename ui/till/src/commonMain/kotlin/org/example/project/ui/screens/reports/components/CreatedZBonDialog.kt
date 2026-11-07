package org.example.project.ui.screens.reports.components

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.features.reports.presentation.components.zBon.ZBonDetailPanel

/**
 * Shows a Z-Bon the operator has just closed when they may not open the Z-Bon history
 * (`ZBON_MANAGE`). It renders the same detail panel the history uses, from the creation response,
 * but without its print and PDF actions: `/api/z-bon/{id}/print` and `/{id}/pdf` are `ZBON_MANAGE`
 * routes and would answer 403. Full screen on a phone, a centred sheet on a tablet.
 */
@Composable
fun CreatedZBonDialog(
    zBon: ZBonDto.ZBonResponseDto,
    compact: Boolean,
    language: Language,
    onDismiss: () -> Unit,
    /** Null when the operator may not pay out tips (no `CASHBOOK_WRITE`); hides the action. */
    onPayOutTip: ((ZBonDto.EmployeeTipPayoutStatusDto) -> Unit)? = null,
    /** Null when the operator may not storno tip payouts; hides "Stornieren". */
    onReverseTipPayout: ((ZBonDto.EmployeeTipPayoutStatusDto, ZBonDto.TipPayoutRecordDto) -> Unit)? = null,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val size =
            if (compact) {
                Modifier.fillMaxSize()
            } else {
                Modifier.widthIn(max = 640.dp).fillMaxHeight(0.9f).padding(16.dp)
            }
        Surface(
            modifier = size,
            shape = if (compact) MaterialTheme.shapes.extraSmall else MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.background,
        ) {
            ZBonDetailPanel(
                zBonData = zBon,
                onClose = onDismiss,
                language = language,
                onPayOutTip = onPayOutTip,
                onReverseTipPayout = onReverseTipPayout,
            )
        }
    }
}
