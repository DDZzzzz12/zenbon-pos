package org.example.project.features.promotion.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Discount
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.data.remote.PromotionApiClient
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.promotion.PromotionDto
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.PromotionKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.LocalDisplayCurrency
import org.example.project.core.utils.toFormattedCurrency
import org.example.project.features.dashboard.presentation.state.AppliedPromotion
import org.example.project.features.promotion.domain.blocksPromotionCombination
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.components.PosTextButton
import org.koin.compose.koinInject

/**
 * Dialog for selecting a promotion to apply during billing.
 *
 * Fetches active promotions and allows the user to pick one.
 * For ITEM_SPECIFIC promotions, shows a sub-picker for selecting the target order item.
 *
 * Enforces application rules:
 * - BILL_WIDE promotions: max 1 per bill
 * - ITEM_SPECIFIC + isMultiUse=false: max 1 per bill
 * - ITEM_SPECIFIC + isMultiUse=true: can apply once per different item
 * - combinable=false: cannot share the bill with another, distinct promotion. Such a row is
 *   rendered disabled with a reason rather than hidden, so the cashier can see why it is barred.
 *   This mirrors the server guard (`PosErrorCode.PROMOTION_NOT_COMBINABLE`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromotionSelectionDialog(
    billItems: List<OrderItemDto.OrderItemResponseDto>,
    appliedPromotions: List<AppliedPromotion> = emptyList(),
    onDismiss: () -> Unit,
    onPromotionSelected: (PromotionDto.PromotionApplyRequestDto, PromotionDto.PromotionResponseDto) -> Unit,
) {
    val promotionApiClient: PromotionApiClient = koinInject()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var promotions by remember { mutableStateOf<List<PromotionDto.PromotionResponseDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<UiError?>(null) }
    var itemPickerPromotion by remember { mutableStateOf<PromotionDto.PromotionResponseDto?>(null) }

    LaunchedEffect(Unit) {
        runCatchingApi { promotionApiClient.getActivePromotions() }
            .onSuccess {
                promotions = it
                isLoading = false
            }.onError { uiError ->
                errorMessage = uiError
                isLoading = false
            }
    }

    val filteredPromotions =
        promotions.filter { promotion ->
            val alreadyApplied = appliedPromotions.filter { it.promotionId == promotion.id }
            when {
                // BILL_WIDE: max 1 per bill
                promotion.scope == "BILL_WIDE" -> alreadyApplied.isEmpty()
                // ITEM_SPECIFIC + !isMultiUse: max 1 per bill
                promotion.scope == "ITEM_SPECIFIC" && !promotion.isMultiUse -> alreadyApplied.isEmpty()
                // ITEM_SPECIFIC + isMultiUse: can apply again (for different items)
                promotion.scope == "ITEM_SPECIFIC" && promotion.isMultiUse -> true
                else -> true
            }
        }

    // Sub-picker for item-specific promotions
    if (itemPickerPromotion != null) {
        val promotion = itemPickerPromotion!!
        ModalBottomSheet(
            onDismissRequest = { itemPickerPromotion = null },
            sheetMaxWidth = 600.dp,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = str(PromotionKeys.targetProduct),
                    style = MaterialTheme.typography.headlineSmall,
                )

                // Expand grouped items into individual (orderItemId, productName, price) entries
                // so each physical item can be targeted independently
                data class SelectableItem(
                    val orderItemId: Long,
                    val productName: String,
                    val unitPrice: String,
                )

                val allItems =
                    billItems.flatMap { grouped ->
                        grouped.orderItemIds.map { itemId ->
                            SelectableItem(itemId, grouped.product.productName, grouped.unitPrice)
                        }
                    }

                val baseItems =
                    if (promotion.targetProductId != null) {
                        val matchingGrouped = billItems.filter { it.product.productId == promotion.targetProductId }
                        matchingGrouped.flatMap { grouped ->
                            grouped.orderItemIds.map { itemId ->
                                SelectableItem(itemId, grouped.product.productName, grouped.unitPrice)
                            }
                        }
                    } else {
                        allItems
                    }

                val alreadyAppliedItemIds =
                    appliedPromotions
                        .filter { it.promotionId == promotion.id }
                        .mapNotNull { it.orderItemId }

                val availableItems = baseItems.filter { it.orderItemId !in alreadyAppliedItemIds }

                if (availableItems.isEmpty()) {
                    Text(
                        text = str(PromotionKeys.noActivePromotions),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    availableItems.forEach { item ->
                        PosCard(
                            variant = PosCardVariant.Plain,
                            onClick = {
                                onPromotionSelected(
                                    PromotionDto.PromotionApplyRequestDto(
                                        promotionId = promotion.id,
                                        orderItemId = item.orderItemId,
                                    ),
                                    promotion,
                                )
                                itemPickerPromotion = null
                            },
                        ) {
                            body {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = item.productName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = item.unitPrice.toFormattedCurrency(),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                PosTextButton(
                    text = str(CommonKeys.cancel),
                    onClick = { itemPickerPromotion = null },
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }

    // Main promotion list dialog
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetMaxWidth = 600.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = str(PromotionKeys.selectPromotion),
                style = MaterialTheme.typography.headlineSmall,
            )

            if (isLoading) {
                CircularProgressIndicator(
                    modifier =
                        Modifier
                            .size(32.dp)
                            .align(Alignment.CenterHorizontally),
                )
            } else if (errorMessage != null) {
                val err = errorMessage!!
                Text(
                    text = str(err.messageKey, *err.messageArgs.toTypedArray()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (filteredPromotions.isEmpty()) {
                Text(
                    text = str(PromotionKeys.noActivePromotions),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                filteredPromotions.forEach { promotion ->
                    // For ITEM_SPECIFIC, check if matching items exist
                    val isItemSpecific = promotion.scope == "ITEM_SPECIFIC"
                    val hasMatchingItems =
                        if (isItemSpecific && promotion.targetProductId != null) {
                            billItems.any { it.product.productId == promotion.targetProductId }
                        } else {
                            !isItemSpecific || billItems.isNotEmpty()
                        }

                    // Same rule the server enforces; disabled rather than hidden so the reason shows.
                    val notCombinable =
                        blocksPromotionCombination(
                            candidateId = promotion.id,
                            candidateCombinable = promotion.combinable,
                            applied = appliedPromotions,
                        )

                    if (!isItemSpecific || hasMatchingItems) {
                        PromotionSelectionItem(
                            promotion = promotion,
                            blockedReason = if (notCombinable) str(PromotionKeys.notCombinable) else null,
                            onClick = {
                                if (isItemSpecific) {
                                    itemPickerPromotion = promotion
                                } else {
                                    onPromotionSelected(
                                        PromotionDto.PromotionApplyRequestDto(
                                            promotionId = promotion.id,
                                            orderItemId = null,
                                        ),
                                        promotion,
                                    )
                                }
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            PosTextButton(
                text = str(CommonKeys.cancel),
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Composable
private fun PromotionSelectionItem(
    promotion: PromotionDto.PromotionResponseDto,
    blockedReason: String?,
    onClick: () -> Unit,
) {
    PosCard(
        onClick = if (blockedReason == null) onClick else null,
        inactive = blockedReason != null,
    ) {
        body {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Type icon
                val icon =
                    when (promotion.discountType) {
                        "PERCENTAGE" -> Icons.Default.Percent
                        "FREE_ITEM" -> Icons.Default.Redeem
                        else -> Icons.Default.Discount
                    }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = promotion.name,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (!promotion.description.isNullOrBlank()) {
                        Text(
                            text = promotion.description.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    val scopeLabel =
                        when (promotion.scope) {
                            "BILL_WIDE" -> str(PromotionKeys.billWide)
                            "ITEM_SPECIFIC" -> "${str(PromotionKeys.itemSpecific)}: ${promotion.targetProductName ?: ""}"
                            else -> promotion.scope
                        }
                    Text(
                        text = scopeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (blockedReason != null) {
                        Text(
                            text = blockedReason,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                // Discount value display
                Text(
                    text =
                        formatPromotionValue(promotion.discountType, promotion.discountValue, LocalDisplayCurrency.current)
                            ?: str(PromotionKeys.freeItem),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
