package org.example.project.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.project.core.presentation.strings.keys.DashboardKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.toFormattedCurrency
import org.example.project.ui.theme.PosSpacing

@Composable
fun PosTableCard(
    tableNumber: String,
    statusColor: Color,
    statusText: String,
    modifier: Modifier = Modifier,
    secondaryText: String = "",
    openAmount: String? = null,
    isActive: Boolean = true,
    isTraining: Boolean = false,
    containerColor: Color? = null,
    borderColor: Color? = null,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
    // True for occupied/reserved tables: adds a visible "Details / Bill" button (and double-click)
    // that opens the same panel as long-press / right-click, which a mouse or touch-only browser
    // station cannot discover on its own.
    showDetailsAction: Boolean = false,
) {
    val resolvedContainerColor = containerColor ?: MaterialTheme.colorScheme.background
    val resolvedBorderColor = borderColor ?: MaterialTheme.colorScheme.outlineVariant
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 90.dp)
                .alpha(if (isActive) 1f else 0.5f)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                    // No onDoubleClick: setting one makes Compose hold every single tap back for the
                    // double-tap timeout, which a till cannot afford. The visible details button below
                    // is the touch alternative to the long press.
                )
                // Desktop and web have no press-and-hold: a right-click is the natural way to
                // ask for a table's context menu, so it opens the same panel as a long press.
                .pointerInput(onLongClick) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                                event.changes.forEach { it.consume() }
                                onLongClick()
                            }
                        }
                    }
                },
        color = resolvedContainerColor,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, resolvedBorderColor),
    ) {
        Box {
            // IntrinsicSize.Min: the card grows with the text (large font scale) and the status
            // stripe still spans its full height.
            Row(
                modifier = Modifier.height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .width(4.dp)
                            .fillMaxHeight()
                            .background(
                                if (isActive) statusColor else MaterialTheme.colorScheme.outlineVariant,
                            ),
                )

                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(horizontal = 12.dp, vertical = PosSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(PosSpacing.xs),
                    horizontalAlignment = Alignment.Start,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = tableNumber,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (openAmount != null) {
                            Text(
                                text = openAmount.toFormattedCurrency(),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }

                    // Keep the left-aligned lines clear of the bottom-end details button.
                    val textEndInset = if (showDetailsAction) 44.dp else 0.dp
                    if (secondaryText.isNotEmpty()) {
                        Text(
                            modifier = Modifier.padding(end = textEndInset),
                            text = secondaryText,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Text(
                        modifier = Modifier.padding(end = textEndInset),
                        text = if (isTraining) "Training" else statusText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isTraining) MaterialTheme.colorScheme.primary else statusColor,
                    )
                }
            }
            if (showDetailsAction) {
                // Bottom-end corner: the table number and amount sit on the top line and the
                // secondary/status text is start-aligned, so this is the one free corner.
                PosIconButton(
                    icon = Icons.Default.Receipt,
                    contentDescription = str(DashboardKeys.tableDetailsAction),
                    onClick = onLongClick,
                    modifier = Modifier.align(Alignment.BottomEnd),
                    size = 48.dp,
                )
            }
        }
    }
}
