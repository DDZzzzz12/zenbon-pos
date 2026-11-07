package org.example.project.admin.features.salesinvoices.presentation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.beleg.BelegFlowStrip
import org.example.project.ui.components.beleg.BelegStage

/**
 * The outgoing Belegfluss strip — Entwurf · Ausgestellt · Offen with the counts of those buckets,
 * the same component the incoming flow uses with a fourth block. Selecting a stage opens its
 * bucket.
 *
 * @param counts the server totals per bucket; empty until loaded, which draws dashes.
 */
@Composable
fun SalesFlowStrip(
    counts: Map<SalesInvoiceBucket, Long>,
    selected: SalesFlowStage,
    onSelect: (SalesFlowStage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val stages =
        SalesFlowStage.entries.map { stage ->
            BelegStage(
                id = stage.name,
                label = str(stage.labelKey()),
                count = if (counts.isEmpty()) null else counts[stage.bucket] ?: 0L,
                icon = stage.icon(),
                sub = str(stage.subKey()),
                selected = stage == selected,
            )
        }
    BelegFlowStrip(
        stages = stages,
        onSelect = { id -> onSelect(SalesFlowStage.valueOf(id)) },
        groupDescription = str(AdminStringKeys.BelegflowStripLabel),
        modifier = modifier,
    )
}

private fun SalesFlowStage.labelKey(): StringKey =
    when (this) {
        SalesFlowStage.DRAFT -> AdminStringKeys.BelegflowSalesDraft
        SalesFlowStage.ISSUED -> AdminStringKeys.SalesInvoicesBucketIssued
        SalesFlowStage.OPEN -> AdminStringKeys.SalesInvoicesBucketOpen
    }

private fun SalesFlowStage.subKey(): StringKey =
    when (this) {
        SalesFlowStage.DRAFT -> AdminStringKeys.BelegflowSalesDraftSub
        SalesFlowStage.ISSUED -> AdminStringKeys.BelegflowSalesIssuedSub
        SalesFlowStage.OPEN -> AdminStringKeys.BelegflowSalesOpenSub
    }

private fun SalesFlowStage.icon(): ImageVector =
    when (this) {
        SalesFlowStage.DRAFT -> Icons.Filled.EditNote
        SalesFlowStage.ISSUED -> Icons.AutoMirrored.Filled.Send
        SalesFlowStage.OPEN -> Icons.Filled.Schedule
    }
