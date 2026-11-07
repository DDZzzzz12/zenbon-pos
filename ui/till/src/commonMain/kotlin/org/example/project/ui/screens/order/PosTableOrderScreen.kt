package org.example.project.ui.screens.order

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.unit.dp
import org.example.project.core.auth.LocalPermissionManager
import org.example.project.core.auth.PriceAuthority
import org.example.project.core.browser.isBrowserClient
import org.example.project.core.data.models.Items
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.presentation.components.notification.LocalGlobalOverlayViewModel
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.ChoiceGroupKeys
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.DashboardKeys
import org.example.project.core.presentation.strings.keys.TableOrderKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.LocalLanguageManager
import org.example.project.core.utils.LocalNotificationViewModel
import org.example.project.core.utils.LocalVirtualKeyboard
import org.example.project.core.utils.LocalWindowSizeClass
import org.example.project.core.utils.isCompact
import org.example.project.features.dashboard.presentation.viewmodel.TableOrderViewModel
import org.example.project.features.notifications.presentation.viewmodel.NotificationUiEvent
import org.example.project.ui.auth.LocalAccessPolicy
import org.example.project.ui.auth.holds
import org.example.project.ui.auth.rememberIsStation
import org.example.project.ui.components.PosDangerButton
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosMarqueeText
import org.example.project.ui.components.PosNavIconButton
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosStatusChip
import org.example.project.ui.components.PosSuccessWatcher
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosTableOrderScreen(
    tableId: Long,
    employeeNumber: String? = null,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TableOrderViewModel = koinViewModel(),
) {
    val windowSize = LocalWindowSizeClass.current
    val uiState by viewModel.uiState.collectAsState()
    var editingItem by remember { mutableStateOf<Items?>(null) }
    var showReview by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }

    val languageManager = LocalLanguageManager.current
    val currentLanguage by languageManager.currentLanguage
    val notificationViewModel = LocalNotificationViewModel.current
    val overlay = LocalGlobalOverlayViewModel.current
    val permissionManager = LocalPermissionManager.current

    // The client half of the guard `OrderServiceImpl` enforces — the same capability, by name, so
    // rule 8 holds by construction rather than by two rules that happen to agree.
    //
    // Was `isManagerOrHigher() || hasPermission(EXCEPTION_MODIFY_PRICES)`. The second leg had been
    // dead since Stage 2a retired per-employee grants; PRICE_OVERRIDE is that intent working, and a
    // group can now confer it without promoting anyone to manager.
    val canModifyPrices = holds(PriceAuthority.CUSTOM_PRICE_AUTHORITY)

    // A steep discount is a separate authority from a custom price. This used to reuse
    // canModifyPrices, so a PRICE_OVERRIDE-only group was offered discounts the server refused
    // (T5-5). Both sides now read PriceAuthority.STEEP_DISCOUNT_AUTHORITY (DISCOUNT_GRANT) — see
    // canApplyHighDiscount in PosEditItemPanel.
    val highDiscountAllowed = canApplyHighDiscount(LocalAccessPolicy.current)

    // Nothing is priced under the order station's shared account — no discount, no custom price
    // (E-2) — and the server refuses such a line under it whatever it holds. That account is exactly
    // the station session (a person's login clears the marker). Read once: the marker cannot change
    // while this screen lives (see rememberIsStation).
    val canPriceLines = linePricingOffered(rememberIsStation())

    // Enable virtual keyboard on all platforms for this screen
    val virtualKeyboard = LocalVirtualKeyboard.current
    DisposableEffect(Unit) {
        // Not in the browser: there the physical keyboard is captured below (see PosProductSearchBar).
        virtualKeyboard.forceVisible = !isBrowserClient
        onDispose { virtualKeyboard.forceVisible = false }
    }

    // Key events only reach onKeyEvent when something inside the node holds focus, and nothing
    // here asks for it, so a freshly opened order screen would ignore the keyboard until the
    // operator happened to tap a focusable control. Claim focus once on open.
    val keyCaptureFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (!isBrowserClient) runCatching { keyCaptureFocus.requestFocus() } }

    // Browser till: the search field is a real input and holds focus, so typing goes straight into
    // it on open. Focus is reclaimed whenever the edit panel or review overlay closes.
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(editingItem == null, showReview) {
        if (isBrowserClient && editingItem == null && !showReview) runCatching { searchFocus.requestFocus() }
    }

    // Load table data + categories on tableId
    LaunchedEffect(tableId) {
        viewModel.setEmployeeNumber(employeeNumber)
        viewModel.loadTable(tableId)
        viewModel.loadCategories()
    }

    // Show loading notification for order submission
    var submitLoadingId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(uiState.isSubmitting) {
        if (uiState.isSubmitting) {
            submitLoadingId =
                overlay.showLoading(
                    title = getString(TableOrderKeys.tableTitle, currentLanguage, uiState.table?.tableNumber ?: ""),
                    message = getString(TableOrderKeys.submittingOrder, currentLanguage),
                )
        } else {
            submitLoadingId?.let {
                overlay.dismissById(it)
                submitLoadingId = null
            }
        }
    }

    // No displayMode: the bus routes by what the error carries — field errors and 5xx still escalate
    // to PosErrorDialog, everything else toasts. Forcing DIALOG turned an operator-fixable refusal
    // (409 ROTATION_POOL_EMPTY when opening a free table) into a modal showing an HTTP status, an
    // error code and a trace id. Toasting loses none of the server's own detail: PosErrorWatcher
    // renders displayMessage(), which carries that sentence onto either surface.
    PosErrorWatcher(
        error = uiState.error,
        title = getString(TableOrderKeys.tableTitle, currentLanguage, uiState.table?.tableNumber ?: ""),
        onClear = { viewModel.clearError() },
        retryAction = { viewModel.loadTable(tableId) },
    )

    // Handle success messages via global notification system
    PosSuccessWatcher(
        message = uiState.successMessage,
        title = getString(TableOrderKeys.tableTitle, currentLanguage, uiState.table?.tableNumber ?: ""),
        onClear = { viewModel.clearSuccessMessage() },
    )

    // Data update events
    LaunchedEffect(Unit) {
        notificationViewModel.notificationEvents.collect { event ->
            when (event) {
                is NotificationUiEvent.DataUpdated -> {
                    viewModel.loadProductsForGrid()
                    viewModel.loadCategories()
                }
                else -> {}
            }
        }
    }

    // Load choice groups for the line being edited so the configurator can render them.
    LaunchedEffect(editingItem?.id) {
        editingItem?.let { item ->
            viewModel.loadChoiceGroupsForProduct(item.product.id)
        }
    }

    // Auto-navigate back on submit success
    LaunchedEffect(uiState.submitSuccess, uiState.isSubmitting) {
        if (uiState.submitSuccess && !uiState.isSubmitting) {
            onNavigateBack()
        }
    }

    val isCompact = windowSize.isCompact()

    if (isCompact) {
        // COMPACT layout
        Box(
            modifier =
                modifier
                    .fillMaxSize()
                    .onKeyEvent { event ->
                        handleKeyEvent(event, viewModel, uiState.searchResults, editingItem != null)
                    }.focusRequester(keyCaptureFocus)
                    .focusable(),
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PosMarqueeText(
                                    text = str(TableOrderKeys.tableTitle, uiState.table?.tableNumber ?: str(TableOrderKeys.tableNotFound)),
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                if (uiState.table != null) {
                                    PosStatusChip(
                                        text = tableStatusLabel(uiState.table!!.tableStatus),
                                        backgroundColor = MaterialTheme.colorScheme.secondaryContainer,
                                        textColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(start = 8.dp),
                                    )
                                }
                            }
                        },
                        navigationIcon = {
                            PosNavIconButton(
                                icon = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = str(TableOrderKeys.backButton),
                                onClick = onNavigateBack,
                                enabled = !uiState.isSubmitting,
                            )
                        },
                        colors =
                            TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                    )
                },
            ) { padding ->
                Column(
                    modifier =
                        Modifier
                            .padding(padding)
                            .fillMaxSize(),
                ) {
                    PosProductSearchBar(
                        focusRequester = searchFocus,
                        value = uiState.keyboardInput,
                        onValueChange = viewModel::setKeyboardInput,
                        onClear = viewModel::clearKeyboardInput,
                        placeholder = str(TableOrderKeys.searchByNameOrSku),
                        onEnter = {
                            if (uiState.searchResults.isNotEmpty()) {
                                viewModel.addProduct(uiState.searchResults.first())
                            }
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                    PosProductGrid(
                        categories = uiState.categories,
                        products = uiState.products,
                        searchResults = uiState.searchResults.ifEmpty { null },
                        favouriteProductIds = uiState.favouriteProductIds,
                        selectedCategoryId = uiState.selectedCategoryId,
                        onCategorySelected = viewModel::selectCategory,
                        onProductSelected = viewModel::addProduct,
                        onProductLongPress = viewModel::onProductLongPress,
                        productIdsWithGroups = uiState.productIdsWithGroups,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )

                    // Floating bar (when items exist)
                    if (uiState.orderItems.isNotEmpty()) {
                        PosOrderFloatingBar(
                            itemCount = uiState.orderItems.sumOf { it.quantity },
                            total = uiState.total,
                            onClick = { showReview = true },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            // Review overlay
            if (showReview) {
                PosOrderReviewScreen(
                    orderItems = uiState.orderItems,
                    discount = uiState.discount,
                    total = uiState.total,
                    onIncreaseQuantity = viewModel::increaseQuantity,
                    onDecreaseQuantity = viewModel::decreaseQuantity,
                    onRemoveItem = viewModel::removeItem,
                    onEditItem = { editingItem = it },
                    onClearAll = viewModel::clearAllItems,
                    onSubmitOrder = viewModel::submitOrder,
                    onBack = { showReview = false },
                    isSubmitting = uiState.isSubmitting,
                )
            }

            // Edit item overlay
            editingItem?.let { item ->
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    PosEditItemPanel(
                        item = item,
                        availableGroups = uiState.choiceGroups,
                        canSetCustomPrice = canModifyPrices,
                        canApplyHighDiscount = highDiscountAllowed,
                        canPriceLines = canPriceLines,
                        onSave = { updatedItem ->
                            viewModel.updateOrderItem(updatedItem)
                            editingItem = null
                        },
                        onDismiss = { editingItem = null },
                    )
                }
            }

            // Configurator overlay (add mode) — driven by pendingConfigItem
            uiState.pendingConfigItem?.let { pending ->
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    PosEditItemPanel(
                        item = pending,
                        availableGroups = uiState.choiceGroups,
                        canSetCustomPrice = canModifyPrices,
                        canApplyHighDiscount = highDiscountAllowed,
                        canPriceLines = canPriceLines,
                        onSave = { configuredItem ->
                            viewModel.confirmConfiguredItem(configuredItem)
                        },
                        onDismiss = {
                            if (uiState.choiceGroups.any { it.isRequired }) {
                                showDiscardDialog = true
                            } else {
                                viewModel.cancelPendingConfig()
                            }
                        },
                    )
                }
            }
        }
    } else {
        // EXPANDED layout
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                PosMarqueeText(
                                    text = str(TableOrderKeys.tableTitle, uiState.table?.tableNumber ?: str(TableOrderKeys.tableNotFound)),
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                if (uiState.table != null) {
                                    Text(
                                        text = str(TableOrderKeys.tableSubtitle, uiState.table!!.locationTypeName, uiState.table!!.seats),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            if (uiState.table != null) {
                                PosStatusChip(
                                    text = tableStatusLabel(uiState.table!!.tableStatus),
                                    backgroundColor = MaterialTheme.colorScheme.secondaryContainer,
                                    textColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(start = 12.dp),
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        PosNavIconButton(
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = str(TableOrderKeys.backButton),
                            onClick = onNavigateBack,
                            enabled = !uiState.isSubmitting,
                        )
                    },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                )
            },
            modifier =
                modifier
                    .onKeyEvent { event ->
                        handleKeyEvent(event, viewModel, uiState.searchResults, editingItem != null)
                    }.focusRequester(keyCaptureFocus)
                    .focusable(),
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                Row(modifier = Modifier.fillMaxSize()) {
                    // Left: product panel
                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        PosProductSearchBar(
                            focusRequester = searchFocus,
                            value = uiState.keyboardInput,
                            onValueChange = viewModel::setKeyboardInput,
                            onClear = viewModel::clearKeyboardInput,
                            placeholder = str(TableOrderKeys.searchByNameOrSku),
                            onEnter = {
                                if (uiState.searchResults.isNotEmpty()) {
                                    viewModel.addProduct(uiState.searchResults.first())
                                }
                            },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                        PosProductGrid(
                            categories = uiState.categories,
                            products = uiState.products,
                            searchResults = uiState.searchResults.ifEmpty { null },
                            favouriteProductIds = uiState.favouriteProductIds,
                            selectedCategoryId = uiState.selectedCategoryId,
                            onCategorySelected = viewModel::selectCategory,
                            onProductSelected = viewModel::addProduct,
                            onProductLongPress = viewModel::onProductLongPress,
                            productIdsWithGroups = uiState.productIdsWithGroups,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    }

                    VerticalDivider()

                    // Right: order sidebar (380dp)
                    PosOrderSidebar(
                        orderItems = uiState.orderItems,
                        discount = uiState.discount,
                        total = uiState.total,
                        onIncreaseQuantity = viewModel::increaseQuantity,
                        onDecreaseQuantity = viewModel::decreaseQuantity,
                        onRemoveItem = viewModel::removeItem,
                        onEditItem = { editingItem = it },
                        onClearAll = viewModel::clearAllItems,
                        onSubmitOrder = viewModel::submitOrder,
                        isSubmitting = uiState.isSubmitting,
                        modifier = Modifier.width(380.dp),
                    )
                }

                // Edit item side panel overlay
                AnimatedVisibility(
                    visible = editingItem != null,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.3f))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { editingItem = null },
                                ),
                    )
                }

                AnimatedVisibility(
                    visible = editingItem != null,
                    enter = slideInHorizontally(initialOffsetX = { it }),
                    exit = slideOutHorizontally(targetOffsetX = { it }),
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    editingItem?.let { item ->
                        Surface(
                            modifier = Modifier.width(400.dp).fillMaxHeight(),
                            shadowElevation = 8.dp,
                            color = MaterialTheme.colorScheme.surface,
                        ) {
                            PosEditItemPanel(
                                item = item,
                                availableGroups = uiState.choiceGroups,
                                canSetCustomPrice = canModifyPrices,
                                canApplyHighDiscount = highDiscountAllowed,
                                canPriceLines = canPriceLines,
                                onSave = { updatedItem ->
                                    viewModel.updateOrderItem(updatedItem)
                                    editingItem = null
                                },
                                onDismiss = { editingItem = null },
                            )
                        }
                    }
                }

                // Configurator scrim (add mode)
                AnimatedVisibility(
                    visible = uiState.pendingConfigItem != null,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.3f))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {
                                        if (uiState.choiceGroups.any { it.isRequired }) {
                                            showDiscardDialog = true
                                        } else {
                                            viewModel.cancelPendingConfig()
                                        }
                                    },
                                ),
                    )
                }

                // Configurator slide-in panel (add mode)
                AnimatedVisibility(
                    visible = uiState.pendingConfigItem != null,
                    enter = slideInHorizontally(initialOffsetX = { it }),
                    exit = slideOutHorizontally(targetOffsetX = { it }),
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    uiState.pendingConfigItem?.let { pending ->
                        Surface(
                            modifier = Modifier.width(400.dp).fillMaxHeight(),
                            shadowElevation = 8.dp,
                            color = MaterialTheme.colorScheme.surface,
                        ) {
                            PosEditItemPanel(
                                item = pending,
                                availableGroups = uiState.choiceGroups,
                                canSetCustomPrice = canModifyPrices,
                                canApplyHighDiscount = highDiscountAllowed,
                                canPriceLines = canPriceLines,
                                onSave = { configuredItem ->
                                    viewModel.confirmConfiguredItem(configuredItem)
                                },
                                onDismiss = {
                                    if (uiState.choiceGroups.any { it.isRequired }) {
                                        showDiscardDialog = true
                                    } else {
                                        viewModel.cancelPendingConfig()
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // Discard confirmation dialog (shared across layouts) for an aborted required configuration.
    if (showDiscardDialog) {
        PosDialog(
            onDismiss = { showDiscardDialog = false },
            title = str(ChoiceGroupKeys.discardItemTitle),
            footer = {
                PosSecondaryButton(
                    text = str(CommonKeys.cancel),
                    onClick = { showDiscardDialog = false },
                )
                PosDangerButton(
                    text = str(CommonKeys.delete),
                    onClick = {
                        showDiscardDialog = false
                        viewModel.cancelPendingConfig()
                    },
                )
            },
        ) {
            Text(
                text = str(ChoiceGroupKeys.discardItemMessage),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * Handles physical keyboard events for product search and selection.
 *
 * Supports:
 * - Backspace: delete last character
 * - Escape: clear input
 * - Enter: add first search result
 * - A-Z, 0-9: append to search input
 */
private fun handleKeyEvent(
    event: androidx.compose.ui.input.key.KeyEvent,
    viewModel: TableOrderViewModel,
    searchResults: List<org.example.project.core.data.models.Product>,
    isEditing: Boolean,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    // The browser till types into the focused search field itself; capturing here would double every key.
    if (isBrowserClient) return false
    // While the edit-item panel is open, never hijack key events from its TextFields.
    if (isEditing) return false

    return when {
        event.key == Key.Backspace -> {
            viewModel.deleteLastCharacter()
            true
        }
        event.key == Key.Escape -> {
            viewModel.clearKeyboardInput()
            true
        }
        event.key == Key.Enter -> {
            if (searchResults.isNotEmpty()) {
                viewModel.addProduct(searchResults.first())
            }
            true
        }
        event.key.keyCode in Key.A.keyCode..Key.Z.keyCode ||
            event.key.keyCode in Key.Zero.keyCode..Key.Nine.keyCode -> {
            // Shortcuts (Ctrl+V, Cmd+R, Alt+…) belong to the browser/OS, not the search buffer.
            if (event.isCtrlPressed || event.isMetaPressed || event.isAltPressed) return false
            viewModel.onKeyboardInput(typedSearchChar(event))
            true
        }
        else -> false
    }
}

/**
 * The character a letter/digit key press types, upper-cased like the search buffer expects.
 *
 * Never derive it from `Key.toString()`: that is platform-specific debug text (`Key: A` on the JVM,
 * a bare key-code number on the browser target), so its last character is a digit in the browser.
 * The key code is only the fallback: letter and digit codes are contiguous on every target.
 */
private fun typedSearchChar(event: androidx.compose.ui.input.key.KeyEvent): String {
    val typed = event.utf16CodePoint
    if (typed > 0 && Char(typed).isLetterOrDigit()) return Char(typed).uppercaseChar().toString()
    val code = event.key.keyCode
    return if (code in Key.A.keyCode..Key.Z.keyCode) {
        ('A' + (code - Key.A.keyCode).toInt()).toString()
    } else {
        ('0' + (code - Key.Zero.keyCode).toInt()).toString()
    }
}

/**
 * The table's status in the operator's language, as the dashboard grid shows it
 * (`PosDashboardGrid`). The model carries the enum name ("FREE"); an unknown value is shown as sent
 * rather than mislabelled.
 */
@Composable
private fun tableStatusLabel(status: String): String =
    when (TableStatus.entries.firstOrNull { it.name == status }) {
        TableStatus.FREE -> str(DashboardKeys.statusFree)
        TableStatus.OCCUPIED -> str(DashboardKeys.statusOccupied)
        TableStatus.RESERVED -> str(DashboardKeys.statusReserved)
        null -> status
    }
