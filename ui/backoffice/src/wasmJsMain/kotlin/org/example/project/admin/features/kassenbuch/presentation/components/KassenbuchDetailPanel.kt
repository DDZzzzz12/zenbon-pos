package org.example.project.admin.features.kassenbuch.presentation.components

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.KassenbuchKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.components.kassenbuch.KassenbuchEntryDetailSections

/**
 * Read-only detail view of one Kassenbuch entry, shown beside the entry table.
 *
 * Assembly rather than rendering: the five detail sections come from shared-ui's
 * [KassenbuchEntryDetailSections], the same composable composeApp's edit panel renders, so the two
 * apps cannot drift on what an entry looks like. Only the panel chrome and the transfer link are
 * added here.
 *
 * The title row stays pinned; everything below it scrolls in its own body, with a visible scrollbar —
 * the sections stack well past an 812 px viewport on a Z-Bon entry (eight positions, the Saldo lines
 * and the TSE block), and a bare `verticalScroll` gave the operator no sign that more was below. It is
 * never hosted in a `PosDialog` (which self-scrolls, and nesting the two crashes the wasm measure
 * pass); its host gives it a bounded width and a bounded height, so the body's `weight` resolves.
 *
 * @param entry The loaded entry detail.
 * @param onClose Clears the selection, collapsing the panel.
 * @param onOpenCounterEntry Selects the other leg of a transfer; only reachable when the server
 *   named exactly one.
 */
@Composable
fun KassenbuchDetailPanel(
    entry: KassenbuchDto.KassenbuchGroupedDetailDto,
    onClose: () -> Unit,
    onOpenCounterEntry: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onOpenLinkedDocument: ((Long) -> Unit)? = null,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "${str(AdminStringKeys.EntryDetailTitle)} · ${entry.belegNr}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            PosIconButton(
                icon = Icons.Default.Close,
                contentDescription = str(CommonKeys.close),
                onClick = onClose,
            )
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(scrollState).padding(end = ScrollbarGutter),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CounterEntrySection(entry = entry, onOpenCounterEntry = onOpenCounterEntry)

                LinkedDocumentSection(entry = entry, onOpenLinkedDocument = onOpenLinkedDocument)

                KassenbuchEntryDetailSections(
                    entry = entry,
                    language = LocalAppLanguage.current,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scrollState),
                modifier = Modifier.align(Alignment.CenterEnd).matchParentSize().wrapContentWidth(Alignment.End),
            )
        }
    }
}

/** Room kept clear at the body's right edge so the scrollbar never covers an amount. */
private val ScrollbarGutter = 12.dp

/**
 * The Beleg this entry is linked to, and a way to open it.
 *
 * Creating a manual entry *requires* a document (Belegzwang) and the server has always sent the
 * link on this DTO, but the web admin never rendered it — so an auditor could see that an entry
 * claimed a receipt and had no way to reach it. composeApp has offered this since it was written;
 * this closes the same gap here.
 *
 * Renders nothing without a document: a Z-Bon aggregate or a Geldtransit leg legitimately has
 * none, and a standing "no receipt" row would read as a defect on every one of them.
 */
@Composable
private fun LinkedDocumentSection(
    entry: KassenbuchDto.KassenbuchGroupedDetailDto,
    onOpenLinkedDocument: ((Long) -> Unit)?,
) {
    val document = entry.linkedDocument ?: return

    PosCard(
        modifier = Modifier.fillMaxWidth(),
        variant = PosCardVariant.Tonal,
    ) {
        body {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = str(KassenbuchKeys.linkedDocument),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = document.filename,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (onOpenLinkedDocument != null) {
                    PosTextButton(
                        text = str(KassenbuchKeys.showLinkedPdf),
                        icon = Icons.AutoMirrored.Filled.ArrowForward,
                        onClick = { onOpenLinkedDocument(document.id) },
                    )
                }
            }
        }
    }
}

/**
 * The Gegenbuchung block: which book the money moved to or from, and a way to open that leg.
 *
 * Renders nothing at all for an entry that is not part of a transfer, which is most of them.
 *
 * A transfer whose counter-leg the server could not name unambiguously still shows the block — the
 * booking IS half of a transfer and hiding that would misrepresent it — but offers no link, and says
 * why in place of the button. See `KassenbuchGroupedDetailDto.counterpartyHeaderId`.
 */
@Composable
private fun CounterEntrySection(
    entry: KassenbuchDto.KassenbuchGroupedDetailDto,
    onOpenCounterEntry: (Long) -> Unit,
) {
    val transferId = entry.transferId ?: return
    val counterHeaderId = entry.counterpartyHeaderId

    PosCard(
        modifier = Modifier.fillMaxWidth(),
        variant = PosCardVariant.Tonal,
    ) {
        body {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = str(AdminStringKeys.KassenbuchCounterEntry),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    // Falls back to the raw transfer id: an operator reconciling a broken pair needs
                    // the value they can search the ledger for, not a blank line.
                    text = entry.counterpartyBookName ?: transferId,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (counterHeaderId != null) {
                    PosTextButton(
                        text = str(AdminStringKeys.KassenbuchOpenCounterEntry),
                        icon = Icons.AutoMirrored.Filled.ArrowForward,
                        onClick = { onOpenCounterEntry(counterHeaderId) },
                    )
                } else {
                    Text(
                        text = str(AdminStringKeys.KassenbuchCounterEntryUnavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
