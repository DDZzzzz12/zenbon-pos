package org.example.project.features.dashboard.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.example.project.core.data.domain.service.ClientOrderService
import org.example.project.core.data.models.*
import org.example.project.core.data.provider.ChoiceGroupDataProvider
import org.example.project.core.data.provider.ProductCategoryDataProvider
import org.example.project.core.data.provider.ProductDataProvider
import org.example.project.core.data.provider.RTableDataProvider
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.components.notification.OverlayEventBus
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.RulesKeys
import org.example.project.core.utils.LanguageManager
import org.example.project.features.dashboard.presentation.state.InputMode
import org.example.project.features.dashboard.presentation.state.TableOrderUiState
import org.example.project.features.rotation.RotationNextStore
import org.example.project.money.Decimal
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * ViewModel for the table order screen.
 *
 * Manages the state and business logic for creating and managing orders
 * for a specific table, including item selection via custom keyboard or
 * visual product grid.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
@OptIn(ExperimentalTime::class)
class TableOrderViewModel(
    private val rTableDataProvider: RTableDataProvider,
    private val productDataProvider: ProductDataProvider,
    private val categoryDataProvider: ProductCategoryDataProvider,
    private val orderService: ClientOrderService,
    private val choiceGroupDataProvider: ChoiceGroupDataProvider,
    private val overlayEventBus: OverlayEventBus,
    private val languageManager: LanguageManager,
    private val rotationNextStore: RotationNextStore,
) : ViewModel() {
    companion object {
        const val FAVOURITES_CATEGORY_ID = -1L
        const val ALL_PRODUCTS = -2L
    }

    private val logger = Logger.withTag("TableOrderViewModel")

    private val _uiState = MutableStateFlow(TableOrderUiState())
    val uiState: StateFlow<TableOrderUiState> = _uiState.asStateFlow()

    private var forEmployeeNumber: String? = null

    /**
     * Who the till showed as the rotation's next employee when this screen was opened for a FREE table.
     * Captured then and never re-read: this screen has no top bar, so if another till opens a table while
     * the waiter composes, the store moves on silently, and expecting the NEW name would hide the shift
     * from the waiter. Null for a table that already had a session, or when nothing was shown.
     */
    private var expectedNextEmployeeId: String? = null

    /** Cache of resolved choice groups per product to avoid refetching on every tap. */
    private val choiceGroupsCache = mutableMapOf<Long, List<ChoiceGroup>>()

    fun setEmployeeNumber(employeeNumber: String?) {
        forEmployeeNumber = employeeNumber
    }

    init {
        loadProductsForGrid()
        loadFavouriteProductIds()
        loadProductIdsWithGroups()
    }

    /**
     * Load the set of product IDs that have at least one choice group attached.
     *
     * Drives the "customizable" badge on the product grid. Non-critical: a failure
     * just means no badges are shown.
     */
    fun loadProductIdsWithGroups() {
        viewModelScope.launch {
            runCatchingApi { choiceGroupDataProvider.getProductIdsWithGroups() }
                .onSuccess { ids -> _uiState.update { it.copy(productIdsWithGroups = ids) } }
                .onError { /* Non-critical: just means no customizable badges */ }
        }
    }

    /**
     * Submit the order to the backend.
     * This function should be called when the order is ready to be submitted.
     * It should also handle any errors that occur during the submission.
     *
     */
    fun submitOrder() {
        logger.i { "TableOrderViewModel.submitOrder: submitting order with ${_uiState.value.orderItems.size} items" }
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isSubmitting = true) }
                val currentState = _uiState.value

                if (currentState.orderItems.isEmpty()) {
                    logger.d { "TableOrderViewModel.submitOrder: no items to submit" }
                    _uiState.update {
                        it.copy(
                            error =
                                UiError.Unknown(
                                    titleKey = CommonKeys.unknownErrorTitle,
                                    messageKey = CommonKeys.unknownErrorMessage,
                                ),
                            isSubmitting = false,
                        )
                    }
                    return@launch
                }
                val table = currentState.table ?: throw Exception("Table not found")

                // One request: for a free table (no session yet) the order itself opens the table on
                // the server, in the transaction that books the items (2026-09-18 e2e finding #3).
                runCatchingApi {
                    orderService.submitOrder(
                        sessionId = table.currentSessionId,
                        items = currentState.orderItems,
                        tableId = table.id,
                        forEmployeeNumber = forEmployeeNumber,
                        expectedNextEmployeeId = expectedNextEmployeeId,
                    )
                }.onSuccess { response ->
                    logger.i { "TableOrderViewModel.submitOrder: order submitted for table=${table.id}" }
                    announceRotationShift(response)
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            submitSuccess = true,
                        )
                    }
                }.onError { uiError ->
                    logger.e { "TableOrderViewModel.submitOrder: failed to submit order: $uiError" }
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            error = uiError,
                        )
                    }
                }
            } catch (e: Exception) {
                logger.e(e) { "TableOrderViewModel.submitOrder: unexpected error" }
                _uiState.update {
                    it.copy(
                        error =
                            UiError.Unknown(
                                titleKey = CommonKeys.unknownErrorTitle,
                                messageKey = CommonKeys.unknownErrorMessage,
                                cause = e,
                            ),
                        isSubmitting = false,
                    )
                }
            }
        }
    }

    /** Tells the cashier who really got the table when the rotation moved on under the till. */
    private fun announceRotationShift(response: OrderDto.OrderResponseDto?) {
        val owner = response?.rotationOwner
        if (response?.rotationShifted != true || owner == null) return
        val lang = languageManager.currentLanguage.value
        overlayEventBus.showInfo(
            title = getString(RulesKeys.roundRobinAssignment, lang),
            message = getString(RulesKeys.rotationShiftedToast, lang, owner.displayName ?: owner.employeeNumber),
        )
    }

    /**
     * Load the choice groups (with options) attached to a product into state.
     *
     * Used by the configurator to render the product's selectable groups.
     * Results are cached per product.
     *
     * @param productId ID of the product whose groups to load
     */
    fun loadChoiceGroupsForProduct(productId: Long) {
        logger.d { "TableOrderViewModel.loadChoiceGroupsForProduct: productId=$productId" }
        viewModelScope.launch {
            runCatchingApi { resolveGroups(productId) }
                .onSuccess { groups ->
                    logger.d { "TableOrderViewModel.loadChoiceGroupsForProduct: loaded ${groups.size} groups for productId=$productId" }
                    _uiState.update { it.copy(choiceGroups = groups) }
                }.onError { uiError ->
                    logger.e { "TableOrderViewModel.loadChoiceGroupsForProduct: failed for productId=$productId" }
                    _uiState.update { it.copy(error = uiError) }
                }
        }
    }

    /** Resolves a product's choice groups, caching the result. */
    private suspend fun resolveGroups(productId: Long): List<ChoiceGroup> =
        choiceGroupsCache.getOrPut(productId) { choiceGroupDataProvider.getGroupsForProduct(productId) }

    /**
     * Load order data for the specified table.
     *
     * Loads table information and product categories for the grid view.
     *
     * @param tableId ID of the table to load order for
     */
    fun loadTable(tableId: Long) {
        logger.i { "TableOrderViewModel.loadTable: tableId=$tableId" }
        // Read now, synchronously, as the tapped table's screen opens — not when the table load returns.
        val shownNext = rotationNextStore.next.value?.employeeId
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            runCatchingApi { rTableDataProvider.getRTableById(tableId) }
                .onSuccess { table ->
                    if (table != null) {
                        // Only a free table is dealt by the rotation; an open one keeps its owner.
                        expectedNextEmployeeId = if (table.currentSessionId == null) shownNext else null
                        _uiState.update { it.copy(table = table, isLoading = false) }
                    } else {
                        _uiState.update {
                            it.copy(
                                error =
                                    UiError.Unknown(
                                        titleKey = CommonKeys.unknownErrorTitle,
                                        messageKey = CommonKeys.unknownErrorMessage,
                                    ),
                                isLoading = false,
                            )
                        }
                    }
                }.onError { uiError ->
                    _uiState.update { it.copy(error = uiError, isLoading = false) }
                }
        }
    }

    fun loadCategories() {
        logger.d { "TableOrderViewModel.loadCategories: loading all categories" }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            runCatchingApi { categoryDataProvider.getAllCategories() }
                .onSuccess { categories ->
                    logger.d { "TableOrderViewModel.loadCategories: loaded ${categories.size} categories" }
                    _uiState.update { it.copy(categories = categories, isLoading = false) }
                }.onError { uiError ->
                    logger.e { "TableOrderViewModel.loadCategories: failed to load categories" }
                    _uiState.update { it.copy(error = uiError, isLoading = false) }
                }
        }
    }

    /**
     * Toggle between keyboard and grid input modes.
     *
     * When switching to GRID mode, loads products for the grid view.
     */
    fun toggleInputMode() {
        _uiState.update { currentState ->
            val newMode =
                when (currentState.inputMode) {
                    InputMode.KEYBOARD -> InputMode.GRID
                    InputMode.GRID -> InputMode.KEYBOARD
                }
            currentState.copy(inputMode = newMode, keyboardInput = "")
        }

        // Load products when switching to grid mode
        if (_uiState.value.inputMode == InputMode.GRID) {
            loadProductsForGrid()
        }
    }

    /**
     * Handle keyboard button press.
     *
     * @param value The value to append (number, letter, or operator)
     */
    fun onKeyboardInput(value: String) {
        _uiState.update { currentState ->
            currentState.copy(keyboardInput = currentState.keyboardInput + value)
        }
        // Trigger product search with updated input
        searchProductsByInput()
    }

    /**
     * Set the keyboard input to an exact value (used by virtual keyboard).
     */
    fun setKeyboardInput(value: String) {
        _uiState.update { it.copy(keyboardInput = value) }
        searchProductsByInput()
    }

    /**
     * Clear the keyboard input.
     */
    fun clearKeyboardInput() {
        _uiState.update { it.copy(keyboardInput = "", searchResults = emptyList()) }
    }

    /**
     * Delete the last character from keyboard input.
     */
    fun deleteLastCharacter() {
        _uiState.update { currentState ->
            val newInput =
                if (currentState.keyboardInput.isNotEmpty()) {
                    currentState.keyboardInput.dropLast(1)
                } else {
                    ""
                }
            currentState.copy(keyboardInput = newInput)
        }
        // Update search results after deletion
        searchProductsByInput()
    }

    private fun extractInput(): Pair<Int, String> {
        val input = _uiState.value.keyboardInput.trim()

        if (input.contains("*")) {
            val quantity = input.substringBeforeLast("*").toIntOrNull() ?: 1
            val productCode = input.substringAfterLast("*").trim()
            return Pair(quantity, productCode)
        }

        return Pair(1, input)
    }

    /**
     * Search for products based on current keyboard input.
     *
     * Searches products by name or product number and updates the UI state
     * with matching results (up to 5 products).
     */
    private fun searchProductsByInput() {
        if (_uiState.value.inputMode != InputMode.KEYBOARD) return
        val (quantity, productCode) = extractInput()

        if (productCode.isEmpty()) {
            _uiState.update { it.copy(searchResults = emptyList()) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(selectedCategoryId = null) }
            runCatchingApi { productDataProvider.searchProducts(productCode) }
                .onSuccess { results -> _uiState.update { it.copy(searchResults = results) } }
                .onError { _uiState.update { it.copy(searchResults = emptyList()) } }
        }
    }

    /**
     * Add item to order from product selection (grid or dropdown).
     *
     * @param product product to add
     */
    fun addProduct(product: Product) {
        viewModelScope.launch {
            try {
                // Use the product directly — it's already a domain model
                val (quantity, productCode) = extractInput()

                // Resolve the product's choice groups; required groups force the configurator open.
                val groups =
                    runCatchingApi { resolveGroups(product.id) }
                        .getOrNull() ?: emptyList()

                if (groups.any { it.isRequired }) {
                    // DON'T commit: open the configurator for required configuration.
                    _uiState.update {
                        it.copy(
                            pendingConfigItem = newItem(product, quantity),
                            choiceGroups = groups,
                            keyboardInput = "",
                        )
                    }
                    return@launch
                }

                // One-tap path: no required groups, add directly.
                // Check if product already exists in order
                val existingItem =
                    _uiState.value.orderItems.find {
                        it.product == product &&
                            !it.customPrice &&
                            it.discount == Decimal.ONE &&
                            !it.transferred &&
                            !it.takeAway &&
                            it.sideNotes == null &&
                            it.extras == null &&
                            it.selectedOptions.isEmpty()
                    }

                if (existingItem != null) {
                    // Increase quantity of existing item
                    increaseQuantity(existingItem.orderItemIds?.first()!!, quantity)
                } else {
                    _uiState.update { currentState ->
                        currentState.copy(
                            orderItems = currentState.orderItems + newItem(product, quantity),
                        )
                    }
                    recalculateTotals()
                }

                _uiState.update {
                    it.copy(
                        keyboardInput = "",
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        error =
                            UiError.Unknown(
                                titleKey = CommonKeys.unknownErrorTitle,
                                messageKey = CommonKeys.unknownErrorMessage,
                                cause = e,
                            ),
                    )
                }
            }
        }
    }

    /**
     * Long-press handler: always open the configurator for the product,
     * even when it only has optional groups.
     *
     * @param product product to configure
     */
    fun onProductLongPress(product: Product) {
        viewModelScope.launch {
            try {
                val (quantity, _) = extractInput()
                val groups =
                    runCatchingApi { resolveGroups(product.id) }
                        .getOrNull() ?: emptyList()
                _uiState.update {
                    it.copy(
                        pendingConfigItem = newItem(product, quantity),
                        choiceGroups = groups,
                        keyboardInput = "",
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        error =
                            UiError.Unknown(
                                titleKey = CommonKeys.unknownErrorTitle,
                                messageKey = CommonKeys.unknownErrorMessage,
                                cause = e,
                            ),
                    )
                }
            }
        }
    }

    /**
     * Commit a configured item, merging into an identical existing line when present.
     *
     * If an existing line shares the same product, price/notes flags, and (sorted)
     * selected options, its quantity is increased; otherwise the item is appended.
     * Clears the pending configuration afterwards.
     *
     * @param item the fully configured item to commit
     */
    fun confirmConfiguredItem(item: Items) {
        _uiState.update { currentState ->
            val existing = currentState.orderItems.firstOrNull { sameConfig(it, item) }
            val updatedItems =
                if (existing != null) {
                    currentState.orderItems.map { line ->
                        if (line === existing) {
                            val newQuantity = line.quantity + item.quantity
                            line.copy(
                                quantity = newQuantity,
                                // sameConfig() only merges lines whose discount and options already
                                // match, so the merged total has to carry both — lineTotalAt re-adds
                                // the option deltas that unitPrice deliberately excludes.
                                totalPrice = line.lineTotalAt(newQuantity),
                            )
                        } else {
                            line
                        }
                    }
                } else {
                    currentState.orderItems + item
                }
            currentState.copy(
                orderItems = updatedItems,
                pendingConfigItem = null,
                choiceGroups = emptyList(),
            )
        }
        recalculateTotals()
    }

    /**
     * Discard the pending configuration without committing it.
     *
     * Used when the cashier cancels a required-group configurator.
     */
    fun cancelPendingConfig() {
        _uiState.update {
            it.copy(
                pendingConfigItem = null,
                choiceGroups = emptyList(),
            )
        }
    }

    /** Builds a fresh order item for a product + quantity (no options applied). */
    private fun newItem(
        product: Product,
        quantity: Int,
    ): Items {
        val now = Clock.System.now().toEpochMilliseconds()
        return Items(
            id = now,
            orderItemIds = listOf(now),
            product = product,
            unitPrice = product.price,
            // A fresh line carries no discount yet (Items.discount defaults to ONE); passing the
            // multiplier explicitly keeps the rounding order identical to every other site.
            totalPrice = orderLineTotal(product.price, quantity, Decimal.ONE),
            quantity = quantity,
        )
    }

    /** True when two items represent the same configuration (used to merge identical lines). */
    private fun sameConfig(
        a: Items,
        b: Items,
    ): Boolean =
        a.product.id == b.product.id &&
            a.customPrice == b.customPrice &&
            a.discount == b.discount &&
            a.takeAway == b.takeAway &&
            a.sideNotes == b.sideNotes &&
            a.selectedOptions.sortedBy { it.optionId } == b.selectedOptions.sortedBy { it.optionId }

    /**
     * Select a product category to filter products in grid view.
     *
     * @param categoryId ID of the category to select (null for all products)
     */
    fun selectCategory(categoryId: Long?) {
        _uiState.update { currentState ->
            currentState.copy(selectedCategoryId = categoryId)
        }

        // Load products for selected category
        loadProductsForGrid()
    }

    /**
     * Load the favourite product ids and pick the grid the order screen opens on: favourites when
     * there are any, otherwise all products. Without the fallback a shop with no favourites, or a
     * failed favourites load, opened the order screen on an empty grid with no tab selected.
     */
    fun loadFavouriteProductIds() {
        viewModelScope.launch {
            runCatchingApi { productDataProvider.getFavouriteProductIds() }
                .onSuccess { data ->
                    val ids = data.toSet()
                    _uiState.update { it.copy(favouriteProductIds = ids) }
                    if (ids.isNotEmpty()) {
                        selectCategory(FAVOURITES_CATEGORY_ID)
                    } else {
                        selectAllProductsIfNothingSelected()
                    }
                }.onError {
                    // Non-critical: no favourites tab, but the grid must still show something.
                    selectAllProductsIfNothingSelected()
                }
        }
    }

    /** The fallback never overrides a category the cashier already picked while favourites loaded. */
    private fun selectAllProductsIfNothingSelected() {
        if (_uiState.value.selectedCategoryId == null) selectCategory(ALL_PRODUCTS)
    }

    /**
     * Load products for grid view based on selected category.
     *
     * Loads all products if no category is selected, or filters by category.
     */
    fun loadProductsForGrid() {
        viewModelScope.launch {
            val selectedCategoryId = _uiState.value.selectedCategoryId
            if (selectedCategoryId != null) {
                _uiState.update { it.copy(searchResults = emptyList(), keyboardInput = "") }
            } else {
                _uiState.update { it.copy(products = emptyList()) }
                return@launch
            }
            when (selectedCategoryId) {
                FAVOURITES_CATEGORY_ID -> {
                    runCatchingApi { productDataProvider.getAllProducts() }
                        .onSuccess { all ->
                            val favouriteIds = _uiState.value.favouriteProductIds
                            val favourites = all.filter { it.id in favouriteIds }
                            _uiState.update { it.copy(products = favourites) }
                        }.onError { _uiState.update { it.copy(products = emptyList()) } }
                    return@launch
                }

                ALL_PRODUCTS -> {
                    runCatchingApi { productDataProvider.getAllProducts() }
                        .onSuccess { products -> _uiState.update { it.copy(products = products) } }
                        .onError { _uiState.update { it.copy(products = emptyList()) } }
                }

                else -> {
                    runCatchingApi { productDataProvider.getProductsByCategory(selectedCategoryId) }
                        .onSuccess { products -> _uiState.update { it.copy(products = products) } }
                        .onError { _uiState.update { it.copy(products = emptyList()) } }
                }
            }
        }
    }

    /**
     * Increase quantity of an order item.
     *
     * @param orderItemId ID of the order item to update
     */
    fun increaseQuantity(
        orderItemId: Long,
        quantity: Int = 1,
    ) {
        _uiState.update { currentState ->
            val updatedItems =
                currentState.orderItems.map { item ->
                    if (item.orderItemIds!!.first() == orderItemId) {
                        val newQuantity = item.quantity + quantity
                        item.copy(
                            quantity = newQuantity,
                            totalPrice = item.lineTotalAt(newQuantity),
                        )
                    } else {
                        item
                    }
                }
            currentState.copy(orderItems = updatedItems)
        }
        recalculateTotals()
    }

    /**
     * Decrease quantity of an order item.
     *
     * @param orderItemId ID of the order item to update
     */
    fun decreaseQuantity(orderItemId: Long) {
        _uiState.update { currentState ->
            val updatedItems =
                currentState.orderItems.mapNotNull { item ->
                    if (item.orderItemIds!!.first() == orderItemId) {
                        if (item.quantity > 1) {
                            val newQuantity = item.quantity - 1
                            item.copy(
                                quantity = newQuantity,
                                totalPrice = item.lineTotalAt(newQuantity),
                            )
                        } else {
                            null // Remove item if quantity would be 0
                        }
                    } else {
                        item
                    }
                }
            currentState.copy(orderItems = updatedItems)
        }
        recalculateTotals()
    }

    /**
     * Remove an item from the order.
     *
     * @param orderItemId ID of the order item to remove
     */
    fun removeItem(orderItemId: Long) {
        _uiState.update { currentState ->
            val updatedItems = currentState.orderItems.filter { it.orderItemIds!!.first() != orderItemId }
            currentState.copy(orderItems = updatedItems)
        }
        recalculateTotals()
    }

    fun clearAllItems() {
        _uiState.update { it.copy(orderItems = emptyList()) }
        recalculateTotals()
    }

    /**
     * Update an existing order item with new values.
     *
     * Updates item properties such as side notes, discount, custom price,
     * and takeaway status. Recalculates totals after update.
     *
     * @param updatedItem The updated order item with new values
     */
    fun updateOrderItem(updatedItem: Items) {
        _uiState.update { currentState ->
            val updatedItems =
                currentState.orderItems.map { item ->
                    if (item.orderItemIds!!.first() == updatedItem.orderItemIds!!.first()) {
                        updatedItem
                    } else {
                        item
                    }
                }
            currentState.copy(orderItems = updatedItems)
        }
        recalculateTotals()
    }

    /**
     * Recalculate order totals (subtotal, tax, total).
     *
     * The line amounts are summed exactly in [Decimal] and stored in the UI state exactly — there is
     * no cents hop anywhere on this screen; display rounding happens only at the formatter.
     */
    private fun recalculateTotals() {
        _uiState.update { currentState ->
            val lineTotal =
                currentState.orderItems
                    .fold(Decimal.ZERO) { acc, item -> acc + item.totalPrice }
            val discountedItems = currentState.orderItems.filter { it.discount != Decimal.ONE }
            var discount = Decimal.ZERO

            if (discountedItems.isNotEmpty()) {
                discountedItems.forEach {
                    // The baseline is this same line at list price and no discount, so it has to
                    // be built the same way as the charged total it is subtracted from — a
                    // different rounding order here invents or hides a cent of "discount", and
                    // omitting extrasDelta measures an undiscounted line WITHOUT its surcharges
                    // against a charged total that has them, which for a cheap product and a
                    // pricey option reports the saving as NEGATIVE.
                    discount += orderLineTotal(
                        it.product.price + it.extrasDelta,
                        it.quantity,
                        Decimal.ONE,
                    ) - it.totalPrice
                }
            }

            currentState.copy(
                total = lineTotal,
                discount = discount,
            )
        }
    }

    /**
     * Clear any error message.
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Clear success message.
     */
    fun clearSuccessMessage() {
        _uiState.update { it.copy(successMessage = null) }
    }
}
