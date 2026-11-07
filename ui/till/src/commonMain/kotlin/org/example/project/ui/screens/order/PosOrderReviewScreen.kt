package org.example.project.ui.screens.order

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosMarqueeText
import org.example.project.ui.components.PosNavIconButton
import org.example.project.ui.components.PosTextButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosOrderReviewScreen(
    orderItems: List<Items>,
    discount: Decimal,
    total: Decimal,
    onIncreaseQuantity: (Long) -> Unit,
    onDecreaseQuantity: (Long) -> Unit,
    onRemoveItem: (Long) -> Unit,
    onEditItem: (Items) -> Unit,
    onClearAll: () -> Unit,
    onSubmitOrder: () -> Unit,
    onBack: () -> Unit,
    isSubmitting: Boolean,
    modifier: Modifier = Modifier,
) {
    var showClearAllDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Spacer(modifier = Modifier.padding(end = 8.dp))
                    PosMarqueeText(
                        text = str(TableOrderKeys.reviewOrder),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    PosNavIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = str(CommonKeys.back),
                        onClick = onBack,
                    )
                },
                actions = {
                    if (orderItems.isNotEmpty()) {
                        val itemCount = orderItems.sumOf { it.quantity }
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
                        Spacer(modifier = Modifier.padding(end = 4.dp))
                        PosTextButton(
                            text = str(TableOrderKeys.clearAll),
                            onClick = { showClearAllDialog = true },
                            colors =
                                ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
        ) {
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

            // Bottom section (not scrollable)
            PosOrderSummaryPanel(
                discount = discount,
                total = total,
                modifier = Modifier.padding(12.dp),
            )

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
