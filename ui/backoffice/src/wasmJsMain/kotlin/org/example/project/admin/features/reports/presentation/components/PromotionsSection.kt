package org.example.project.admin.features.reports.presentation.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.PromotionKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosAdaptiveColumn
import org.example.project.ui.components.PosAdaptiveDataTable
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardRole
import org.example.project.ui.components.PosTableAlign
import org.example.project.ui.components.PosTableColumn
import org.example.project.ui.components.PosTableMin
import org.example.project.ui.components.PosTableRow
import org.example.project.ui.components.formatMoneyForDisplay
import org.example.project.ui.components.formatPercentForDisplay

@Composable
fun PromotionsSection(
    promotions: StatisticsDto.PromotionBreakdownResponseDto,
    compareEnabled: Boolean = false,
) {
    val currency = promotions.currency
    val language = LocalAppLanguage.current

    PosCard(modifier = Modifier.fillMaxWidth()) {
        body {
            Text(
                text = str(AdminStringKeys.ReportsPromotionsTitle),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            if (promotions.promotions.isEmpty()) {
                Text(
                    text = str(AdminStringKeys.ReportsPromotionsEmpty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val columns =
                    buildList {
                        add(PosAdaptiveColumn(PosTableColumn(str(AdminStringKeys.ReportsColPromotion), PosTableAlign.Start, weight = 1.8f), PosCardRole.Title))
                        add(PosAdaptiveColumn(PosTableColumn(str(AdminStringKeys.ReportsColType), PosTableAlign.Start, weight = 1.2f)))
                        add(PosAdaptiveColumn(PosTableColumn(str(AdminStringKeys.ReportsColTimes), PosTableAlign.End)))
                        add(
                            PosAdaptiveColumn(
                                PosTableColumn(str(AdminStringKeys.ReportsColDeducted), PosTableAlign.End, minWidth = PosTableMin.Money, nowrap = true),
                                PosCardRole.Trailing,
                            ),
                        )
                        if (compareEnabled) {
                            add(PosAdaptiveColumn(PosTableColumn(str(AdminStringKeys.ReportsColTimesDelta), PosTableAlign.End)))
                            add(
                                PosAdaptiveColumn(
                                    PosTableColumn(
                                        str(AdminStringKeys.ReportsColDeductedDelta),
                                        PosTableAlign.End,
                                        minWidth = PosTableMin.Money,
                                        nowrap = true,
                                    ),
                                ),
                            )
                        }
                    }
                val rows =
                    promotions.promotions.map { p ->
                        PosTableRow(
                            cells =
                                buildList {
                                    add(p.name)
                                    add(discountTypeLabel(p.discountType))
                                    add(p.timesApplied.toString())
                                    add(formatMoneyForDisplay(p.totalDeducted, currency))
                                    if (compareEnabled) {
                                        add(deltaCell(p.timesAppliedDeltaPercent, language))
                                        add(deltaCell(p.totalDeductedDeltaPercent, language))
                                    }
                                },
                        )
                    } +
                        PosTableRow(
                            cells =
                                buildList {
                                    add(str(AdminStringKeys.ReportsTotalRow))
                                    add("")
                                    add("")
                                    add(formatMoneyForDisplay(promotions.totalDeducted, currency))
                                    if (compareEnabled) {
                                        add("")
                                        add("")
                                    }
                                },
                            emphasized = true,
                        )
                PosAdaptiveDataTable(columns, rows)
            }
        }
    }
}

@Composable
private fun discountTypeLabel(discountType: String): String =
    when (discountType) {
        "FIXED_AMOUNT" -> str(PromotionKeys.fixedAmount)
        "PERCENTAGE" -> str(PromotionKeys.percentage)
        "FREE_ITEM" -> str(PromotionKeys.freeItem)
        else -> discountType
    }

private fun deltaCell(
    deltaPercent: String?,
    language: Language,
): String = deltaPercent?.let { formatPercentForDisplay(it, language) }.orEmpty()
