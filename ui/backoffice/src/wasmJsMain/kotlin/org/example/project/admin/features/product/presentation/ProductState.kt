package org.example.project.admin.features.product.presentation

import org.example.project.core.domain.dto.pfand.PfandTypeDto
import org.example.project.core.domain.dto.product.ChoiceGroupDto
import org.example.project.core.domain.dto.product.ProductCategoryDto
import org.example.project.core.domain.dto.product.ProductChoiceGroupDto
import org.example.project.core.domain.dto.product.ProductDto
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.utils.file.PickedFile
import org.example.project.money.Decimal
import org.example.project.ui.components.inventory.CategoryFormField
import org.example.project.ui.components.inventory.ProductFormField

enum class ProductSort {
    NAME_ASC,
    NAME_DESC,
    NUMBER_ASC,
    NUMBER_DESC,
    CATEGORY_ASC,
    CATEGORY_DESC,
    PRICE_ASC,
    PRICE_DESC,
}

sealed interface ProductState {
    data object Loading : ProductState

    data class Error(
        val error: UiError,
    ) : ProductState

    data class Data(
        val categories: List<ProductCategoryDto.ProductCategoryResponseDto>,
        val allProducts: List<ProductDto.ProductResponseDto>,
        val selectedCategoryId: Int? = null,
        val search: String = "",
        val sort: ProductSort = ProductSort.NAME_ASC,
        val allGroups: List<ChoiceGroupDto.ChoiceGroupResponseDto> = emptyList(),
        val allProductGroupLinks: List<ProductChoiceGroupDto.ResponseDto> = emptyList(),
        /** Active Pfand types, offered by the product form's Pfand-Typ selector. */
        val pfandTypes: List<PfandTypeDto.PfandTypeResponseDto> = emptyList(),
    ) : ProductState {
        val visibleProducts: List<ProductDto.ProductResponseDto>
            get() =
                allProducts
                    .filter { selectedCategoryId == null || it.categoryId == selectedCategoryId }
                    .filter {
                        search.isBlank() ||
                            it.name.contains(search, ignoreCase = true) ||
                            it.productNumber.contains(search, ignoreCase = true)
                    }.let { list ->
                        when (sort) {
                            ProductSort.NAME_ASC -> list.sortedBy { it.name.lowercase() }
                            ProductSort.NAME_DESC -> list.sortedByDescending { it.name.lowercase() }
                            ProductSort.NUMBER_ASC -> list.sortedBy { it.productNumber.lowercase() }
                            ProductSort.NUMBER_DESC -> list.sortedByDescending { it.productNumber.lowercase() }
                            ProductSort.CATEGORY_ASC -> list.sortedBy { it.categoryName.lowercase() }
                            ProductSort.CATEGORY_DESC -> list.sortedByDescending { it.categoryName.lowercase() }
                            // Decimal, not toDoubleOrNull(): the server's prices are decimal strings
                            // and every other money path already parses them through :platform-money. Decimal
                            // implements Comparable<Decimal>, so sortedBy works directly.
                            ProductSort.PRICE_ASC -> list.sortedBy { Decimal.parseOrNull(it.price) ?: Decimal.ZERO }
                            ProductSort.PRICE_DESC -> list.sortedByDescending { Decimal.parseOrNull(it.price) ?: Decimal.ZERO }
                        }
                    }

        fun productCountFor(categoryId: Int): Int = allProducts.count { it.categoryId == categoryId }

        /**
         * Active products filed under [categoryId]. While this is non-zero the category may not be
         * deleted: the server refuses with 409 `DELETE_HAS_REFERENCES` (QA MD-02), and the delete
         * dialog disables its button and says why. Inactive products do not block the delete.
         */
        fun activeProductCountFor(categoryId: Int): Int = allProducts.count { it.categoryId == categoryId && it.isActive }
    }
}

/** Draft state for the product edit/create drawer. */
data class ProductEditor(
    val id: Long? = null, // null = creating
    val productNumber: String = "",
    val name: String = "",
    val en: String = "",
    val ch: String = "",
    val description: String = "",
    val price: String = "",
    val categoryId: Int? = null,
    /**
     * Deliberately unset on a new product: the type drives the VAT mapping, so a silent default is a
     * tax risk (FO-54). Required before the product can be saved.
     */
    val productType: String? = null,
    val isActive: Boolean = true,
    val allowCustomPrice: Boolean = false,
    val imageUrl: String? = null, // existing image
    val imagePicked: PickedFile? = null, // newly chosen
    val attachedGroupIds: List<Int> = emptyList(), // ordered choice-group attachment
    /**
     * The Pfand type whose deposit is charged with this product, or null for none.
     *
     * Always sent back on save: the server reads an absent `pfandTypeId` as "remove the link".
     */
    val pfandTypeId: Long? = null,
    /** The linked type's name as the server reported it — shown even when the type is no longer active. */
    val pfandTypeName: String? = null,
    /**
     * True for a Pfand type's own deposit or return product. Its link is fixed (the server refuses
     * to move it), so the form shows it read-only.
     */
    val isPfandArticle: Boolean = false,
    val saving: Boolean = false,
    /** "save" — an unexpected save failure, rendered from a fixed key. Field validation lives in [fieldErrors]. */
    val fieldError: String? = null,
    /** Per-field validation refusals from the Save click, shown at the field. */
    val fieldErrors: Map<ProductFormField, StringKey> = emptyMap(),
    /** Bumped on every Save click the validation refuses; the drawer scrolls to the first error on each bump. */
    val validationAttempt: Int = 0,
    /** A server refusal, rendered localized through `displayMessage()` (admin21 NOTE-d21). */
    val saveError: UiError? = null,
) {
    val isCreate: Boolean get() = id == null

    /** This editor with the transient flags neutralised, so only user-visible edits are compared. */
    private fun comparable(): ProductEditor = copy(saving = false, fieldError = null, fieldErrors = emptyMap(), validationAttempt = 0, saveError = null)

    /** True when the form differs from [original] (the state it was opened or last loaded in). */
    fun isDirtyComparedTo(original: ProductEditor?): Boolean = original != null && comparable() != original.comparable()
}

/** Draft state for the category edit/create drawer. */
data class CategoryEditor(
    val id: Int? = null,
    val name: String = "",
    val en: String = "",
    val ch: String = "",
    val description: String = "",
    val sortPosition: String = "",
    val isActive: Boolean = true,
    val visibility: String = "EVERYWHERE",
    val saving: Boolean = false,
    /** "save" — an unexpected save failure, rendered from a fixed key. Field validation lives in [fieldErrors]. */
    val fieldError: String? = null,
    /** Per-field validation refusals from the Save click, shown at the field. */
    val fieldErrors: Map<CategoryFormField, StringKey> = emptyMap(),
    /** Bumped on every Save click the validation refuses; the drawer scrolls to the first error on each bump. */
    val validationAttempt: Int = 0,
    /** A server refusal, rendered localized through `displayMessage()`. */
    val saveError: UiError? = null,
) {
    val isCreate: Boolean get() = id == null

    /** This editor with the transient flags neutralised, so only user-visible edits are compared. */
    private fun comparable(): CategoryEditor = copy(saving = false, fieldError = null, fieldErrors = emptyMap(), validationAttempt = 0, saveError = null)

    /** True when the form differs from [original] (the state it was opened in). */
    fun isDirtyComparedTo(original: CategoryEditor?): Boolean = original != null && comparable() != original.comparable()
}
