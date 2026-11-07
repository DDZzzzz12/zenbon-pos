/**
 * Reusable read-only display sections for a grouped Kassenbuch entry.
 *
 * Extracted from composeApp's KassenbuchDetailPanel so both the desktop/Android
 * edit panel and the wasm admin detail screen render the same five sections:
 * - Basic information (entry type, booking number, date, employee, locked status)
 * - Transaction summary (total, VAT line items with sub-balances, balances)
 * - DATEV details (bu, gegenkonto, kost1, kost2, nachricht) when present
 * - TSE information (transaction id, number, signed timestamp, signature) when signed
 * - Description (booking text, created timestamp)
 *
 * Read-only: no action buttons, dialogs, or panel chrome — the host supplies those.
 */
package org.example.project.ui.components.kassenbuch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.keys.KassenbuchKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.enumStrings.getName
import org.example.project.core.utils.formatShortDateTime
import org.example.project.core.utils.shownBookingText
import org.example.project.core.utils.toDecimalOrZero
import org.example.project.money.Decimal
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.components.TseSigningNoteSection
import org.example.project.ui.components.formatMoneyForDisplay
import org.example.project.ui.components.formatPercentForDisplay
import org.example.project.ui.theme.PosSpacing

/**
 * Renders the five read-only detail sections of a grouped Kassenbuch entry.
 *
 * @param entry Grouped kassenbuch entry detail to display
 * @param language Current app language for localized strings
 * @param modifier Layout modifier applied to the outer column
 */
@Composable
fun KassenbuchEntryDetailSections(
    entry: KassenbuchDto.KassenbuchGroupedDetailDto,
    language: Language,
    modifier: Modifier = Modifier,
) {
    val isIncoming = entry.entryType.isIncoming
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BasicInformationSection(entry, isIncoming, language)

        TransactionSummarySection(
            entry = entry,
            previousBalance = previousBalanceOrNull(entry),
            isIncoming = isIncoming,
            language = language,
        )

        DatevDetailsSection(entry, language)

        TseInformationSection(entry, language)

        // Nachsignierung: pending, signed late or abandoned, and why
        TseSigningNoteSection(entry.tseSigningNote)

        // Booked past the shop's Beleg-age limit: why, as the operator recorded it
        entry.lateBookingReason?.takeIf { it.isNotBlank() }?.let { reason ->
            LateBookingNoteSection(reason)
        }

        DescriptionSection(entry, language)
    }
}

/**
 * The backdating override's trace on the entry: its Beleg date was older than the shop's limit, and
 * this is the reason recorded when it was booked (with a manager's authority). Shown so an auditor
 * reading the entry sees the exception documented where it happened.
 */
@Composable
private fun LateBookingNoteSection(reason: String) {
    Column {
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Text(
            text = str(KassenbuchKeys.lateBookingReason),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(text = reason, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Balance before this entry = current balance − this entry's total, on exact decimals.
 *
 * Both operands arrive from the server as decimal strings, so the subtraction happens in
 * [Decimal] and never round-trips through Double. Returns null when either value is
 * unparseable, which hides the row rather than showing a wrong number.
 */
private fun previousBalanceOrNull(entry: KassenbuchDto.KassenbuchGroupedDetailDto): String? {
    val balance = Decimal.parseOrNull(entry.currentBalance) ?: return null
    val total = Decimal.parseOrNull(entry.totalAmount) ?: return null
    return formatMoneyForDisplay((balance - total).toPlainString(), entry.waehrung)
}

/**
 * Basic information section showing entry type, booking number, date, and employee.
 */
@Composable
private fun BasicInformationSection(
    entry: KassenbuchDto.KassenbuchGroupedDetailDto,
    isIncoming: Boolean,
    language: Language,
) {
    PosCard(
        modifier = Modifier.fillMaxWidth(),
        variant = PosCardVariant.Elevated,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
    ) {
        body {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Entry type with color-coded icon
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (isIncoming) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                        contentDescription = null,
                        tint =
                            if (isIncoming) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        modifier = Modifier.size(32.dp),
                    )
                    Column {
                        Text(
                            text = str(KassenbuchKeys.entryType),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            text = entry.entryType.getName(language),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color =
                                if (isIncoming) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                        )
                    }
                }

                HorizontalDivider()

                // Booking number and date
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        InfoRow(
                            label = str(KassenbuchKeys.bookingNumber),
                            value = "#${entry.belegNr}",
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        InfoRow(
                            label = str(KassenbuchKeys.date),
                            value = entry.belegDatum,
                        )
                    }
                }

                // Employee and locked status
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    InfoRow(
                        label = str(KassenbuchKeys.employee),
                        value = entry.employeeNumber,
                    )

                    if (entry.isLocked) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = str(KassenbuchKeys.isLocked),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        } // body
    }
}

/**
 * Transaction summary section showing total amount, line items breakdown, and balances.
 *
 * @param entry Grouped kassenbuch entry detail data
 * @param previousBalance Balance before this transaction (pre-formatted), null if first entry
 * @param isIncoming Whether this is an incoming (positive) transaction
 * @param language Current app language for localized strings
 */
@Composable
private fun TransactionSummarySection(
    entry: KassenbuchDto.KassenbuchGroupedDetailDto,
    previousBalance: String?,
    isIncoming: Boolean,
    language: Language,
) {
    PosCard(
        modifier = Modifier.fillMaxWidth(),
        variant = PosCardVariant.Elevated,
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        body {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = str(KassenbuchKeys.transactionSummary),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Total amount - Large and prominent
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = str(KassenbuchKeys.total),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = formatMoneyForDisplay(entry.totalAmount, entry.waehrung, forceSign = true),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color =
                            if (isIncoming) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                    )
                }

                // Line items breakdown - use detail line items if available (with sub-balances)
                val detailLineItems = entry.lineItems
                if (detailLineItems.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Text(
                        text = str(KassenbuchKeys.vatLineItems),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )

                    detailLineItems.forEachIndexed { index, lineItem ->
                        DetailLineItemRow(
                            lineItem = lineItem,
                            index = index,
                            waehrung = entry.waehrung,
                            language = language,
                            showsVatRate = entry.entryType.carriesVatRate,
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Previous balance (if available)
                previousBalance?.let { prevBalance ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = str(KassenbuchKeys.previousBalance),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(
                            text = prevBalance,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                }

                // Current balance
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = str(KassenbuchKeys.currentBalance),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = formatMoneyForDisplay(entry.currentBalance, entry.waehrung),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        } // body
    }
}

/**
 * DATEV details section showing accounting fields.
 *
 * Displays bu (booking key), gegenkonto (counter account), kost1, kost2,
 * and nachricht (message) fields when available.
 */
@Composable
private fun DatevDetailsSection(
    entryDetail: KassenbuchDto.KassenbuchGroupedDetailDto,
    language: Language,
) {
    // Only show if at least one DATEV field has a value
    val hasDatevFields =
        listOfNotNull(
            entryDetail.bu,
            entryDetail.gegenkonto,
            entryDetail.kost1,
            entryDetail.kost2,
            entryDetail.nachricht,
        ).any { it.isNotBlank() }

    if (!hasDatevFields) return

    PosCard(
        modifier = Modifier.fillMaxWidth(),
        variant = PosCardVariant.Elevated,
        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        body {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = str(KassenbuchKeys.datevDetails),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Display DATEV fields in a grid-like layout
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Booking Key (BU)
                    entryDetail.bu?.takeIf { it.isNotBlank() }?.let { bu ->
                        Column(modifier = Modifier.weight(1f)) {
                            DatevInfoRow(
                                label = str(KassenbuchKeys.bookingKey),
                                value = bu,
                            )
                        }
                    }

                    // Counter Account (Gegenkonto)
                    entryDetail.gegenkonto?.takeIf { it.isNotBlank() }?.let { gegenkonto ->
                        Column(modifier = Modifier.weight(1f)) {
                            DatevInfoRow(
                                label = str(KassenbuchKeys.counterAccount),
                                value = gegenkonto,
                            )
                        }
                    }
                }

                // Cost Centers
                val hasCostCenters =
                    listOfNotNull(entryDetail.kost1, entryDetail.kost2)
                        .any { it.isNotBlank() }

                if (hasCostCenters) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        entryDetail.kost1?.takeIf { it.isNotBlank() }?.let { kost1 ->
                            Column(modifier = Modifier.weight(1f)) {
                                DatevInfoRow(
                                    label = str(KassenbuchKeys.costCenter1),
                                    value = kost1,
                                )
                            }
                        }

                        entryDetail.kost2?.takeIf { it.isNotBlank() }?.let { kost2 ->
                            Column(modifier = Modifier.weight(1f)) {
                                DatevInfoRow(
                                    label = str(KassenbuchKeys.costCenter2),
                                    value = kost2,
                                )
                            }
                        }
                    }
                }

                // Message (Nachricht)
                entryDetail.nachricht?.takeIf { it.isNotBlank() }?.let { nachricht ->
                    DatevInfoRow(
                        label = str(KassenbuchKeys.notes),
                        value = nachricht,
                    )
                }
            }
        } // body
    }
}

/**
 * TSE information section showing fiscal signature details.
 *
 * Displays TSE transaction ID, signature, transaction number, and signed timestamp.
 */
@Composable
private fun TseInformationSection(
    entryDetail: KassenbuchDto.KassenbuchGroupedDetailDto,
    language: Language,
) {
    PosCard(
        modifier = Modifier.fillMaxWidth(),
        variant = PosCardVariant.Elevated,
        containerColor =
            if (entryDetail.tseTransactionId != null) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
    ) {
        body {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = str(KassenbuchKeys.tseInformation),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )

                    // TSE status badge
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color =
                            if (entryDetail.tseTransactionId != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector =
                                    if (entryDetail.tseTransactionId != null) {
                                        Icons.Default.Verified
                                    } else {
                                        Icons.Default.Warning
                                    },
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint =
                                    if (entryDetail.tseTransactionId != null) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                            )
                            Text(
                                text =
                                    if (entryDetail.tseTransactionId != null) {
                                        "TSE"
                                    } else {
                                        str(KassenbuchKeys.tseNotSigned)
                                    },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color =
                                    if (entryDetail.tseTransactionId != null) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                            )
                        }
                    }
                }

                if (entryDetail.tseTransactionId != null) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // TSE Transaction ID
                    TseInfoRow(
                        label = str(KassenbuchKeys.tseTransactionId),
                        value = entryDetail.tseTransactionId.toString(),
                    )

                    // TSE Transaction Number
                    entryDetail.tseTransactionNumber?.let { transactionNumber ->
                        TseInfoRow(
                            label = str(KassenbuchKeys.tseTransactionNumber),
                            value = transactionNumber.toString(),
                        )
                    }

                    // TSE Signed At
                    entryDetail.tseSignedAt?.let { signedAt ->
                        TseInfoRow(
                            label = str(KassenbuchKeys.tseSignedAt),
                            value = signedAt.formatShortDateTime(language),
                        )
                    }

                    // TSE Signature (truncated for display)
                    entryDetail.tseSignature?.let { signature ->
                        Column(
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = str(KassenbuchKeys.tseSignature),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surface,
                            ) {
                                Text(
                                    text = if (signature.length > 40) "${signature.take(40)}..." else signature,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(8.dp),
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }
            }
        } // body
    }
}

/**
 * Description section showing booking text and created timestamp.
 */
@Composable
private fun DescriptionSection(
    entryDetail: KassenbuchDto.KassenbuchGroupedDetailDto,
    language: Language,
) {
    PosCard(
        modifier = Modifier.fillMaxWidth(),
        variant = PosCardVariant.Elevated,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        body {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = str(KassenbuchKeys.description),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Booking text
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = str(KassenbuchKeys.bookingText),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = entryDetail.belegtext.shownBookingText(),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                // Created timestamp
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                InfoRow(
                    label = str(KassenbuchKeys.createdAt),
                    value = entryDetail.createdAt.formatShortDateTime(language),
                )
            }
        } // body
    }
}

/**
 * Detail line item row with sub-balance information.
 *
 * @param lineItem Line item detail DTO with sub-balance
 * @param index Zero-based index of the line item
 * @param waehrung ISO currency code of the entry (e.g. "EUR"), rendered as its symbol
 * @param language Current app language for localized strings
 * @param showsVatRate False for an entry type with no VAT implication — the stored rate is not shown
 */
@Composable
private fun DetailLineItemRow(
    lineItem: KassenbuchDto.KassenbuchLineItemDetailDto,
    index: Int,
    waehrung: String,
    language: Language,
    showsVatRate: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = str(KassenbuchKeys.lineItem, index + 1),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    lineItem.belegtext?.let { text ->
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // VAT rate badge. An entry type with no USt-Bezug (Geldtransit, Privateinlage, …) is
                    // booked non-taxable whatever rate the row stores — rows written before the server
                    // pinned it still hold the form's "19" default, which is not the booking.
                    val vatBadgeText =
                        if (showsVatRate) {
                            lineItem.ustSatz?.let { formatPercentForDisplay(it, language) }
                        } else {
                            str(KassenbuchKeys.vatRateNotApplicable)
                        }
                    vatBadgeText?.let { badgeText ->
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                        ) {
                            Text(
                                text = badgeText,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = PosSpacing.sm, vertical = 2.dp),
                            )
                        }
                    }

                    // Kind badge (only for non-STANDARD kinds)
                    LineKindBadge(lineItem.kind, lineItem.paymentTypeName)

                    // Amount in the SAME sign as the „Gesamt" above it — the stored cash-flow sign —
                    // so the lines add up to the total the reader sees. The earlier document-sign
                    // display (spec 2026-07-07 §5.1) put „+1,00 €" under „Gesamt −1,00 €" for every
                    // withdrawal (F60). Entry and correction FORMS still take the document sign
                    // (§1.3); only this read-only view changed. Exact in Decimal, never a Double.
                    val displayAmount = lineItem.vorzBetrag.toDecimalOrZero()
                    Text(
                        text = formatMoneyForDisplay(displayAmount.toPlainString(), waehrung, forceSign = true),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color =
                            if (!displayAmount.isNegative) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                    )
                }
            }

            // Sub-balance row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${str(KassenbuchKeys.subBalance)}: ${formatMoneyForDisplay(lineItem.subBalance, waehrung)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Helper composable for displaying label-value pairs in basic info.
 */
@Composable
private fun InfoRow(
    label: String,
    value: String,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/**
 * Helper composable for displaying DATEV field label-value pairs.
 */
@Composable
private fun DatevInfoRow(
    label: String,
    value: String,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

/**
 * Helper composable for displaying TSE field label-value pairs.
 */
@Composable
private fun TseInfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}
