package org.example.project.features.dashboard.presentation.state

import org.example.project.core.data.models.*
import org.example.project.core.presentation.error.UiError
import org.example.project.money.Decimal

/**
 * UI state for the table order screen.
 *
 * Manages the state of order items, product selection, and input modes
 * for adding items to an order.
 *
 * All monetary values are exact [Decimal]s — no `Double`, no cents — so the order totals
 * carry the same precision the line amounts were summed at.
 *
 * @property isLoading Whether data is being loaded
 * @property table The table for this order
 * @property orderItems List of items in the current order
 * @property selectedCategoryId Currently selected product category
 * @property inputMode Current input mode (KEYBOARD or GRID)
 * @property keyboardInput Current keyboard input string
 * @property discount Discount amount
 * @property total Final order total
 * @property error Error message if any
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
data class TableOrderUiState(
    val isLoading: Boolean = false,
    val isSubmitting: Boolean = false,
    val submitSuccess: Boolean = false,
    val table: RTable? = null,
    val orderItems: List<Items> = emptyList(),
    val categories: List<ProductCategory> = emptyList(),
    val products: List<Product> = emptyList(),
    val searchResults: List<Product> = emptyList(),
    val selectedCategoryId: Long? = null,
    val favouriteProductIds: Set<Long> = emptySet(),
    val inputMode: InputMode = InputMode.KEYBOARD,
    val keyboardInput: String = "",
    val discount: Decimal = Decimal.ZERO,
    val total: Decimal = Decimal.ZERO,
    /** Choice groups (with options) for the product currently being configured. */
    val choiceGroups: List<ChoiceGroup> = emptyList(),
    /** Product IDs that have at least one choice group attached (drives the customizable badge). */
    val productIdsWithGroups: Set<Long> = emptySet(),
    /** Item awaiting configuration in the configurator (drives open-on-add). Null when none pending. */
    val pendingConfigItem: Items? = null,
    val error: UiError? = null,
    val successMessage: String? = null,
)

/**
 * Input mode for adding items to order.
 */
enum class InputMode {
    KEYBOARD, // Custom keyboard for product codes
    GRID, // Visual product grid selection
}
