package org.example.project.ui.screens.order

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.core.data.models.Items
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.TableOrderKeys
import org.example.project.core.presentation.strings.str
import org.example.project.money.Decimal
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosDangerIconButton
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosTextButton

@Composable
fun PosOrderSidebar(
    orderItems: List<Items>,
    discount: Decimal,
    total: Decimal,
    onIncreaseQuantity: (Long) -> Unit,
    onDecreaseQuantity: (Long) -> Unit,
    onRemoveItem: (Long) -> Unit,
    onEditItem: (Items) -> Unit,
    onClearAll: () -> Unit,
    onSubmitOrder: () -> Unit,
    isSubmitting: Boolean,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var showClearAllDialog by remember { mutableStateOf(false) }

    LaunchedEffect(orderItems.size) {
        if (orderItems.isNotEmpty()) {
            listState.animateScrollToItem(orderItems.size - 1)
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
    ) {
        // Header
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = str(TableOrderKeys.currentOrder),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            if (orderItems.isNotEmpty()) {
                val itemCount = orderItems.sumOf { it.quantity }
                Spacer(modifier = Modifier.padding(start = 8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                    modifier = Modifier.size(24.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "$itemCount",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (orderItems.isNotEmpty()) {
                PosDangerIconButton(
                    icon = Icons.Default.DeleteSweep,
                    contentDescription = str(TableOrderKeys.clearAll),
                    onClick = { showClearAllDialog = true },
                )
            }
        }

        HorizontalDivider()

        // Order items list
        if (orderItems.isEmpty()) {
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = str(TableOrderKeys.noItemsYet),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(orderItems, key = { it.id }) { item ->
                    PosOrderItemRow(
                        item = item,
                        onIncrease = { onIncreaseQuantity(item.id) },
                        onDecrease = { onDecreaseQuantity(item.id) },
                        onRemove = { onRemoveItem(item.id) },
                        onEdit = { onEditItem(item) },
                    )
                }
            }
        }

        HorizontalDivider()

        // Order summary
        PosOrderSummaryPanel(
            discount = discount,
            total = total,
            modifier = Modifier.padding(12.dp),
        )

        // Submit button
        PosButton(
            text = str(TableOrderKeys.submitOrder),
            onClick = onSubmitOrder,
            isLoading = isSubmitting,
            enabled = orderItems.isNotEmpty(),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
        )
    }

    // Clear All confirmation dialog
    if (showClearAllDialog) {
        PosDialog(
            onDismiss = { showClearAllDialog = false },
            title = str(TableOrderKeys.clearAllConfirmTitle),
            footer = {
                PosTextButton(
                    text = str(CommonKeys.cancel),
                    onClick = { showClearAllDialog = false },
                )
                PosTextButton(
                    text = str(CommonKeys.confirm),
                    onClick = {
                        showClearAllDialog = false
                        onClearAll()
                    },
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                )
            },
        ) {
            Text(text = str(TableOrderKeys.clearAllConfirmMessage))
        }
    }
}
