package org.example.project.admin.features.reports.presentation.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.enumStrings.categoryDisplayName
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
fun CatalogSection(
    catalog: StatisticsDto.CatalogBreakdownResponseDto,
    compareEnabled: Boolean = false,
) {
    val currency = catalog.currency
    val language = LocalAppLanguage.current

    PosCard(modifier = Modifier.fillMaxWidth()) {
        body {
            Text(
                text = str(AdminStringKeys.ReportsCatalogTitle),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            // Top products (by revenue)
            SubTitle(str(AdminStringKeys.ReportsTopProductsTitle))
            val products = catalog.bestSellers.topProductsByRevenue
            if (products.isEmpty()) {
                EmptyLine()
            } else {
                PosAdaptiveDataTable(
                    columns =
                        buildList {
                            add(
                                PosAdaptiveColumn(
                                    PosTableColumn(str(AdminStringKeys.ReportsColProduct), PosTableAlign.Start, weight = 1.6f),
                                    PosCardRole.Title,
                                ),
                            )
                            add(PosAdaptiveColumn(PosTableColumn(str(AdminStringKeys.ReportsColCategory), PosTableAlign.Start, weight = 1.2f)))
                            add(PosAdaptiveColumn(PosTableColumn(str(AdminStringKeys.ReportsColQty), PosTableAlign.End)))
                            add(
                                PosAdaptiveColumn(
                                    PosTableColumn(str(AdminStringKeys.ReportsColRevenue), PosTableAlign.End, minWidth = PosTableMin.Money, nowrap = true),
                                    PosCardRole.Trailing,
                                ),
                            )
                            if (compareEnabled) {
                                add(PosAdaptiveColumn(PosTableColumn(str(AdminStringKeys.ReportsColGrowth), PosTableAlign.End)))
                            }
                        },
                    rows =
                        products.map { p ->
                            PosTableRow(
                                cells =
                                    buildList {
                                        add(p.productName)
                                        add(categoryDisplayName(p.categoryName, language))
                                        add(p.quantitySold.toString())
                                        add(formatMoneyForDisplay(p.revenue, currency))
                                        if (compareEnabled) {
                                            add(growthCell(p.growthRate, language))
                                        }
                                    },
                            )
                        },
                )
            }

            HorizontalDivider()

            // Categories
            SubTitle(str(AdminStringKeys.ReportsCategoriesTitle))
            val categories = catalog.categories.categoryMetrics
            if (categories.isEmpty()) {
                EmptyLine()
            } else {
                PosAdaptiveDataTable(
                    columns =
                        buildList {
                            add(
                                PosAdaptiveColumn(
                                    PosTableColumn(str(AdminStringKeys.ReportsColCategory), PosTableAlign.Start, weight = 1.6f),
                                    PosCardRole.Title,
                                ),
                            )
                            add(PosAdaptiveColumn(PosTableColumn(str(AdminStringKeys.ReportsColQty), PosTableAlign.End)))
                            add(
                                PosAdaptiveColumn(
                                    PosTableColumn(str(AdminStringKeys.ReportsColRevenue), PosTableAlign.End, minWidth = PosTableMin.Money, nowrap = true),
                                    PosCardRole.Trailing,
                                ),
                            )
                            if (compareEnabled) {
                                add(PosAdaptiveColumn(PosTableColumn(str(AdminStringKeys.ReportsColGrowth), PosTableAlign.End)))
                            }
                        },
                    rows =
                        categories.map { c ->
                            PosTableRow(
                                cells =
                                    buildList {
                                        add(categoryDisplayName(c.categoryName, language))
                                        add(c.totalQuantitySold.toString())
                                        add(formatMoneyForDisplay(c.totalRevenue, currency))
                                        if (compareEnabled) {
                                            add(growthCell(c.growthRate, language))
                                        }
                                    },
                            )
                        } +
                            catalogReconciliationRows(
                                categories = catalog.categories,
                                currency = currency,
                                labels =
                                    CatalogReconciliationLabels(
                                        billPromotions = str(AdminStringKeys.ReportsCategoryBillPromotions),
                                        ownerTips = str(AdminStringKeys.ReportsCategoryOwnerTips),
                                        total = str(AdminStringKeys.ReportsCategoryTotal),
                                    ),
                                columnCount = if (compareEnabled) 4 else 3,
                            ),
                )
            }

            HorizontalDivider()

            // Extras
            SubTitle(str(AdminStringKeys.ReportsExtrasTitle))
            if (catalog.extras.isEmpty()) {
                EmptyLine()
            } else {
                Text(
                    text = str(AdminStringKeys.ReportsExtrasIncludedHint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PosAdaptiveDataTable(
                    columns =
                        listOf(
                            PosAdaptiveColumn(PosTableColumn(str(AdminStringKeys.ReportsColExtra), PosTableAlign.Start, weight = 2f), PosCardRole.Title),
                            PosAdaptiveColumn(PosTableColumn(str(AdminStringKeys.ReportsColTimes), PosTableAlign.End)),
                            PosAdaptiveColumn(
                                PosTableColumn(str(AdminStringKeys.ReportsColRevenue), PosTableAlign.End, minWidth = PosTableMin.Money, nowrap = true),
                                PosCardRole.Trailing,
                            ),
                        ),
                    rows =
                        catalog.extras.map { e ->
                            val name = if (e.groupName != null) "${e.groupName} · ${e.optionName}" else e.optionName
                            PosTableRow(listOf(name, e.timesChosen.toString(), formatMoneyForDisplay(e.addedRevenue, currency)))
                        },
                )
            }
        }
    }
}

@Composable
private fun SubTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun EmptyLine() {
    Text(
        text = str(AdminStringKeys.ReportsCatalogEmpty),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun growthCell(
    growthRate: String?,
    language: Language,
): String = growthRate?.let { formatPercentForDisplay(it, language) }.orEmpty()
