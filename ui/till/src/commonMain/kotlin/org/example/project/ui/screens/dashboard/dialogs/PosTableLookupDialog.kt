package org.example.project.ui.screens.dashboard.dialogs

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.example.project.core.data.models.RTable
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.keys.DashboardKeys
import org.example.project.core.presentation.strings.keys.TableNumberKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosTextField
import org.example.project.ui.theme.PosSpacing
import org.example.project.ui.theme.PosTheme

/**
 * Table number lookup dialog using PosDialog design language.
 *
 * Displays a search field with live filtering and keyboard-navigable results list.
 * Arrow Up/Down moves focus through results. Enter behavior:
 * - **Single Enter**: Selects the focused table (navigates to order screen)
 * - **Double Enter** (within 500ms): Selects and triggers secondary action (open bottom sheet)
 *
 * @param isVisible Whether the dialog is currently shown
 * @param language Current UI language for translations
 * @param searchResults List of tables matching the current input
 * @param onSearchQueryChanged Callback when input text changes (triggers search)
 * @param onDismiss Callback when dialog should be dismissed
 * @param onTableSelected Callback with the selected [RTable] on single Enter
 * @param onTableDoubleEnter Callback with the selected [RTable] on double Enter
 * @param onTableLongPress Callback with the selected [RTable] on long press (e.g., show detail panel)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PosTableLookupDialog(
    isVisible: Boolean,
    language: Language,
    searchResults: List<RTable>,
    onSearchQueryChanged: (String) -> Unit,
    onDismiss: () -> Unit,
    onTableSelected: (RTable) -> Unit,
    onTableDoubleEnter: (RTable) -> Unit = {},
    onTableLongPress: (RTable) -> Unit = {},
) {
    if (!isVisible) return

    var tableNumber by remember { mutableStateOf("") }
    var focusedIndex by remember { mutableStateOf(-1) }
    val focusRequester = remember { FocusRequester() }

    // Double-Enter detection state
    var pendingTable by remember { mutableStateOf<RTable?>(null) }
    var confirmWindowKey by remember { mutableStateOf(0) }

    // Reset input and auto-focus when dialog opens
    LaunchedEffect(Unit) {
        tableNumber = ""
        focusedIndex = -1
        pendingTable = null
        onSearchQueryChanged("")
        focusRequester.requestFocus()
    }

    // Reset selection index when search results change
    LaunchedEffect(searchResults) {
        focusedIndex = if (searchResults.isNotEmpty()) 0 else -1
    }

    // Double-Enter timer: after 500ms without a second Enter, fire single-Enter action
    LaunchedEffect(confirmWindowKey) {
        val table = pendingTable ?: return@LaunchedEffect
        delay(500L)
        // Timer expired without second Enter -> single-Enter action
        pendingTable = null
        onTableSelected(table)
    }

    val confirmAction = {
        if (focusedIndex in searchResults.indices) {
            val table = searchResults[focusedIndex]
            if (pendingTable != null) {
                // Second Enter within 500ms -> double-Enter action
                pendingTable = null
                onTableDoubleEnter(table)
            } else {
                // First Enter -> start 500ms window
                pendingTable = table
                confirmWindowKey++
            }
        }
    }

    PosDialog(
        onDismiss = onDismiss,
        title = str(TableNumberKeys.title),
        titleIcon = Icons.Default.Search,
        maxWidth = 480.dp,
    ) {
        // Search field
        PosTextField(
            value = tableNumber,
            onValueChange = { newValue ->
                tableNumber = newValue
                onSearchQueryChanged(newValue)
            },
            label = str(TableNumberKeys.placeholder),
            leadingIcon = Icons.Default.Search,
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Enter -> {
                                confirmAction()
                                true
                            }
                            Key.Escape -> {
                                onDismiss()
                                true
                            }
                            Key.DirectionDown -> {
                                if (searchResults.isNotEmpty() && focusedIndex < searchResults.lastIndex) {
                                    focusedIndex++
                                }
                                true
                            }
                            Key.DirectionUp -> {
                                if (focusedIndex > 0) {
                                    focusedIndex--
                                }
                                true
                            }
                            else -> false
                        }
                    },
        )

        // Results list
        if (searchResults.isNotEmpty() && tableNumber.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
            ) {
                itemsIndexed(searchResults) { index, table ->
                    val isFocused = index == focusedIndex

                    val tableStatus =
                        try {
                            TableStatus.valueOf(table.tableStatus)
                        } catch (_: IllegalArgumentException) {
                            TableStatus.FREE
                        }

                    val statusColor =
                        when {
                            !table.isActive -> MaterialTheme.colorScheme.onSurfaceVariant
                            tableStatus == TableStatus.FREE -> PosTheme.colors.posAvailable
                            tableStatus == TableStatus.OCCUPIED -> PosTheme.colors.posOccupied
                            tableStatus == TableStatus.RESERVED -> PosTheme.colors.posReserved
                            else -> PosTheme.colors.posAvailable
                        }

                    val statusText =
                        when (tableStatus) {
                            TableStatus.FREE -> str(DashboardKeys.statusFree)
                            TableStatus.OCCUPIED -> str(DashboardKeys.statusOccupied)
                            TableStatus.RESERVED -> str(DashboardKeys.statusReserved)
                        }

                    val bgColor =
                        if (isFocused) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.background
                        }

                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(bgColor)
                                .combinedClickable(
                                    onClick = { onTableSelected(table) },
                                    onLongClick = { onTableLongPress(table) },
                                ).padding(vertical = PosSpacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Left accent bar (4dp wide, full row height)
                        Box(
                            modifier =
                                Modifier
                                    .width(4.dp)
                                    .height(40.dp)
                                    .background(statusColor),
                        )

                        // Table info
                        Row(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            // Table number (bold)
                            Column {
                                Text(
                                    text = str(DashboardKeys.tableLabel) + " " + table.tableNumber,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "${table.seats} ${str(DashboardKeys.seatsLabel)}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            // Status text
                            Text(
                                text = statusText,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = statusColor,
                            )
                        }
                    }
                }
            }
        }

        // Empty state
        if (searchResults.isEmpty() && tableNumber.isNotEmpty()) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = str(TableNumberKeys.tableNotFound, tableNumber),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
