package org.example.project.admin.features.product.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.example.project.core.data.remote.ChoiceGroupApiClient
import org.example.project.core.data.remote.PfandTypeApiClient
import org.example.project.core.data.remote.ProductApiClient
import org.example.project.core.data.remote.ProductCategoryApiClient
import org.example.project.core.data.remote.ProductChoiceGroupApiClient
import org.example.project.core.domain.dto.pfand.PfandTypeDto
import org.example.project.core.domain.dto.product.ProductCategoryDto
import org.example.project.core.domain.dto.product.ProductChoiceGroupDto
import org.example.project.core.domain.dto.product.ProductDto
import org.example.project.core.network.error.PosApiException
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.error.toUiError
import org.example.project.core.utils.SystemRecords
import org.example.project.core.utils.toCanonicalPriceOrNull
import org.example.project.core.utils.toEditableDecimal

class ProductViewModel(
    private val productApi: ProductApiClient,
    private val categoryApi: ProductCategoryApiClient,
    private val productChoiceGroupApi: ProductChoiceGroupApiClient,
    private val choiceGroupApi: ChoiceGroupApiClient,
    private val pfandTypeApi: PfandTypeApiClient,
) : ViewModel() {
    private val log = Logger.withTag("products")

    var state by mutableStateOf<ProductState>(ProductState.Loading)
        private set

    private var filterToRestore: ProductState.Data? = null

    /**
     * Driven by the screen on entry (VM is retained app-wide). Reloads the data but keeps the applied
     * search, category and sort: the destination is rebuilt by a window resize across 600dp and by
     * re-navigation, and the operator's filter must survive both.
     */
    fun refresh() {
        // Loading/Error carry no filter: a second refresh during a load (or a retry) reuses the first one's.
        val applied = (state as? ProductState.Data) ?: filterToRestore
        filterToRestore = applied
        state = ProductState.Loading
        viewModelScope.launch {
            runCatchingApi {
                coroutineScope {
                    val categories = categoryApi.getAllProductCategories()
                    val products = productApi.getAllProducts()
                    val groups = choiceGroupApi.getAll().items
                    val links = productChoiceGroupApi.getAll().items
                    val pfandTypes = loadPfandTypes()
                    ProductState.Data(
                        categories = categories,
                        allProducts = products,
                        allGroups = groups,
                        allProductGroupLinks = links,
                        pfandTypes = pfandTypes,
                        // A category deleted meanwhile would otherwise leave an invisible empty filter.
                        selectedCategoryId = applied?.selectedCategoryId?.takeIf { id -> categories.any { it.id == id } },
                        search = applied?.search.orEmpty(),
                        sort = applied?.sort ?: ProductSort.NAME_ASC,
                    )
                }
            }.onSuccess {
                filterToRestore = null
                state = it
            }.onError(retry = ::refresh) { uiError ->
                log.e { "load products failed" }
                state = ProductState.Error(uiError)
            }
        }
    }

    fun clearError() {
        state = ProductState.Loading
    }

    private inline fun withData(block: (ProductState.Data) -> ProductState.Data) {
        (state as? ProductState.Data)?.let { state = block(it) }
    }

    fun selectCategory(categoryId: Int?) = withData { it.copy(selectedCategoryId = categoryId) }

    fun onSearch(text: String) = withData { it.copy(search = text) }

    fun onSort(sort: ProductSort) = withData { it.copy(sort = sort) }

    var editor by mutableStateOf<ProductEditor?>(null)
        private set
    var categoryEditor by mutableStateOf<CategoryEditor?>(null)
        private set

    /** The product editor as opened (and as last loaded): the pristine copy [isEditorDirty] compares against. */
    private var editorOriginal by mutableStateOf<ProductEditor?>(null)
    private var categoryEditorOriginal by mutableStateOf<CategoryEditor?>(null)

    /** True when the open product editor differs from what was loaded; a clean form never prompts. */
    val isEditorDirty: Boolean get() = editor?.isDirtyComparedTo(editorOriginal) == true

    /** True when the open category editor differs from what it was opened with. */
    val isCategoryEditorDirty: Boolean get() = categoryEditor?.isDirtyComparedTo(categoryEditorOriginal) == true

    // ----- Product editor -----
    fun openCreateProduct() {
        // FO-54: preselect only the category currently filtered on; the type stays unset (VAT risk).
        val data = state as? ProductState.Data
        val filteredCat = data?.selectedCategoryId?.takeIf { id -> data.categories.any { it.id == id } }
        editor = ProductEditor(categoryId = filteredCat)
        editorOriginal = editor
        categoryEditor = null
        categoryEditorOriginal = null
    }

    fun openEditProduct(p: ProductDto.ProductResponseDto) {
        if (!SystemRecords.mayEditProduct(p.productType)) {
            log.w { "edit refused locally (id=${p.id}): system product" }
            return
        }
        editor =
            ProductEditor(
                id = p.id,
                productNumber = p.productNumber,
                name = p.name,
                en = p.en ?: "",
                ch = p.ch ?: "",
                description = p.description ?: "",
                price = p.price.toEditableDecimal(),
                categoryId = p.categoryId,
                productType = p.productType,
                isActive = p.isActive,
                allowCustomPrice = p.allowCustomPrice,
                imageUrl = p.imageUrl,
                pfandTypeId = p.pfandTypeId,
                pfandTypeName = p.pfandTypeName,
                isPfandArticle = p.isDeposit || p.isDepositReturn,
            )
        editorOriginal = editor
        categoryEditor = null
        categoryEditorOriginal = null
        viewModelScope.launch {
            try {
                val attachedGroupIds = productChoiceGroupApi.getForProduct(p.id).groups.map { it.id }
                // The load itself is not an edit: re-pin the pristine copy with the loaded attachments.
                if (editor?.id == p.id) {
                    editorOriginal = editorOriginal?.copy(attachedGroupIds = attachedGroupIds)
                    editor = editor?.copy(attachedGroupIds = attachedGroupIds)
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Throwable) {
                // leave attachedGroupIds empty on failure
            }
        }
    }

    fun onEditorField(block: (ProductEditor) -> ProductEditor) {
        editor = editor?.let { old -> block(old).withEditedErrorsCleared(old) }
    }

    fun closeEditor() {
        editor = null
        editorOriginal = null
    }

    // ----- choice-group attachment editing -----
    fun attachGroup(id: Int) {
        val e = editor ?: return
        if (id in e.attachedGroupIds) return
        editor = e.copy(attachedGroupIds = e.attachedGroupIds + id)
    }

    fun detachGroup(id: Int) {
        editor = editor?.let { it.copy(attachedGroupIds = it.attachedGroupIds.filterNot { gid -> gid == id }) }
    }

    fun moveAttachedGroup(
        id: Int,
        up: Boolean,
    ) {
        val e = editor ?: return
        val ordered = e.attachedGroupIds.toMutableList()
        val idx = ordered.indexOf(id)
        val target = if (up) idx - 1 else idx + 1
        if (idx < 0 || target !in ordered.indices) return
        ordered.add(target, ordered.removeAt(idx))
        editor = e.copy(attachedGroupIds = ordered)
    }

    fun saveEditor() {
        val e = editor ?: return
        // Client half of the price guard (admin21 NOTE-d21): a value typed with a comma and saved
        // before the field lost focus reached the server raw and came back as a 400.
        val errors = validateProductDraft(e)
        // Category and type are covered by [errors]; the null checks only narrow the types.
        val catId = e.categoryId
        val productType = e.productType
        if (errors.isNotEmpty() || catId == null || productType == null) {
            editor = e.copy(fieldErrors = errors, validationAttempt = e.validationAttempt + 1, fieldError = null, saveError = null)
            return
        }
        val price = if (e.price.isBlank()) "0" else e.price.toCanonicalPriceOrNull() ?: return
        editor = e.copy(saving = true, fieldError = null, fieldErrors = emptyMap(), saveError = null)
        viewModelScope.launch {
            try {
                val imageUrl = e.imagePicked?.let { productApi.uploadProductImage(it.bytes, it.fileName).imageUrl } ?: e.imageUrl
                val savedId: Long =
                    if (e.isCreate) {
                        productApi
                            .createProduct(
                                ProductDto.CreateProductRequestDto(
                                    productNumber = e.productNumber,
                                    name = e.name,
                                    en = e.en.ifBlank { null },
                                    ch = e.ch.ifBlank { null },
                                    description = e.description.ifBlank { null },
                                    price = price,
                                    categoryId = catId,
                                    productType = productType,
                                    isActive = e.isActive,
                                    allowCustomPrice = e.allowCustomPrice,
                                    imageUrl = imageUrl,
                                    pfandTypeId = e.pfandTypeId,
                                ),
                            ).id
                    } else {
                        productApi
                            .updateProduct(
                                e.id!!,
                                ProductDto.UpdateProductRequestDto(
                                    productNumber = e.productNumber,
                                    name = e.name,
                                    en = e.en.ifBlank { null },
                                    ch = e.ch.ifBlank { null },
                                    description = e.description.ifBlank { null },
                                    price = price,
                                    categoryId = catId,
                                    productType = productType,
                                    isActive = e.isActive,
                                    allowCustomPrice = e.allowCustomPrice,
                                    imageUrl = imageUrl,
                                    pfandTypeId = e.pfandTypeId,
                                ),
                            ).id
                    }
                productChoiceGroupApi.attach(ProductChoiceGroupDto.AttachRequestDto(productId = savedId, groupIds = e.attachedGroupIds))
                editor = null
                editorOriginal = null
                reloadProducts()
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: PosApiException) {
                editor = editor?.copy(saving = false, saveError = ex.toUiError())
            } catch (ex: Throwable) {
                editor = editor?.copy(saving = false, fieldError = "save")
            }
        }
    }

    fun deleteProduct(id: Long) {
        val product = (state as? ProductState.Data)?.allProducts?.firstOrNull { it.id == id }
        if (product != null && !SystemRecords.mayEditProduct(product.productType)) {
            log.w { "delete refused locally (id=$id): system product" }
            return
        }
        viewModelScope.launch {
            try {
                productApi.deleteProduct(id)
                reloadProducts()
            } catch (
                ex: CancellationException,
            ) {
                throw ex
            } catch (ex: Throwable) {
                log.e(ex) { "delete failed (id=$id) — keeping current data" }
            }
        }
    }

    // ----- Category editor -----
    fun openCreateCategory() {
        categoryEditor = CategoryEditor()
        categoryEditorOriginal = categoryEditor
        editor = null
        editorOriginal = null
    }

    fun openEditCategory(c: ProductCategoryDto.ProductCategoryResponseDto) {
        if (!SystemRecords.mayEditCategory(c.name)) {
            log.w { "edit refused locally (id=${c.id}): system category" }
            return
        }
        categoryEditor =
            CategoryEditor(
                id = c.id,
                name = c.name,
                en = c.en ?: "",
                ch = c.ch ?: "",
                description = c.description ?: "",
                sortPosition = c.sortPosition.toString(),
                isActive = c.isActive,
                visibility = c.visibility,
            )
        categoryEditorOriginal = categoryEditor
        editor = null
        editorOriginal = null
    }

    fun onCategoryEditorField(block: (CategoryEditor) -> CategoryEditor) {
        categoryEditor = categoryEditor?.let { old -> block(old).withEditedErrorsCleared(old) }
    }

    fun closeCategoryEditor() {
        categoryEditor = null
        categoryEditorOriginal = null
    }

    fun saveCategoryEditor() {
        val e = categoryEditor ?: return
        val errors = validateCategoryDraft(e)
        if (errors.isNotEmpty()) {
            categoryEditor = e.copy(fieldErrors = errors, validationAttempt = e.validationAttempt + 1, fieldError = null, saveError = null)
            return
        }
        categoryEditor = e.copy(saving = true, fieldError = null, fieldErrors = emptyMap(), saveError = null)
        viewModelScope.launch {
            try {
                if (e.isCreate) {
                    categoryApi.createProductCategory(
                        ProductCategoryDto.CreateProductCategoryRequestDto(
                            name = e.name,
                            en = e.en.ifBlank { null },
                            ch = e.ch.ifBlank { null },
                            description = e.description.ifBlank { null },
                            isActive = e.isActive,
                            sortOrder = e.sortPosition.toDoubleOrNull() ?: 0.0,
                            visibility = e.visibility,
                        ),
                    )
                } else {
                    categoryApi.updateProductCategory(
                        e.id!!.toLong(),
                        ProductCategoryDto.UpdateProductCategoryRequestDto(
                            name = e.name,
                            en = e.en.ifBlank { null },
                            ch = e.ch.ifBlank { null },
                            description = e.description.ifBlank { null },
                            isActive = e.isActive,
                            visibility = e.visibility,
                        ),
                    )
                }
                categoryEditor = null
                categoryEditorOriginal = null
                reloadCategories()
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: PosApiException) {
                categoryEditor = categoryEditor?.copy(saving = false, saveError = ex.toUiError())
            } catch (ex: Throwable) {
                categoryEditor = categoryEditor?.copy(saving = false, fieldError = "save")
            }
        }
    }

    fun deleteCategory(id: Int) {
        // Client half of the system-record guard: PFAND and VOUCHER are never deleted.
        val category = (state as? ProductState.Data)?.categories?.firstOrNull { it.id == id }
        if (category != null && !SystemRecords.mayEditCategory(category.name)) {
            log.w { "delete refused locally (id=$id): system category" }
            return
        }
        // Client half of the MD-02 guard: never send a delete the server will refuse with 409.
        val blocking = (state as? ProductState.Data)?.activeProductCountFor(id) ?: 0
        if (blocking > 0) {
            log.w { "delete refused locally (id=$id): $blocking active products" }
            return
        }
        viewModelScope.launch {
            try {
                categoryApi.deleteProductCategory(id.toLong())
                reloadCategories()
            } catch (
                ex: CancellationException,
            ) {
                throw ex
            } catch (ex: Throwable) {
                log.e(ex) { "delete failed (id=$id) — keeping current data" }
            }
        }
    }

    /**
     * Moves a category one slot up/down in the sidebar order and persists the whole list's
     * [sortPosition] via updateCategoryPositions. Optimistic; reverts to server state on failure.
     */
    fun moveCategory(
        id: Int,
        up: Boolean,
    ) {
        val data = state as? ProductState.Data ?: return
        val ordered = data.categories.sortedBy { it.sortPosition }.toMutableList()
        val idx = ordered.indexOfFirst { it.id == id }
        if (idx < 0) return
        val target = if (up) idx - 1 else idx + 1
        if (target !in ordered.indices) return
        ordered.add(target, ordered.removeAt(idx))
        val reordered = ordered.mapIndexed { i, c -> c.copy(sortPosition = i.toDouble()) }
        withData { it.copy(categories = reordered) }
        viewModelScope.launch {
            try {
                categoryApi.updateCategoryPositions(
                    reordered.map {
                        ProductCategoryDto.UpdateProductCategoryPositionRequestDto(categoryId = it.id, sortOrder = it.sortPosition)
                    },
                )
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Throwable) {
                reloadCategories()
            }
        }
    }

    // ----- Category bulk choice-group attach/remove -----

    /** A refused bulk attach/detach, shown by `PosErrorWatcher` on the screen (FO-75: never swallowed). */
    var bulkError by mutableStateOf<UiError?>(null)
        private set

    fun clearBulkError() {
        bulkError = null
    }

    fun bulkAttachGroupToCategory(
        categoryId: Int,
        groupId: Int,
    ) {
        runBulk(retry = { bulkAttachGroupToCategory(categoryId, groupId) }) {
            productChoiceGroupApi.bulkAttachToCategory(
                ProductChoiceGroupDto.BulkCategoryAttachRequestDto(categoryId = categoryId, groupIds = listOf(groupId)),
            )
        }
    }

    fun bulkRemoveGroupFromCategory(
        categoryId: Int,
        groupId: Int,
    ) {
        runBulk(retry = { bulkRemoveGroupFromCategory(categoryId, groupId) }) {
            productChoiceGroupApi.bulkRemoveFromCategory(categoryId, groupId)
        }
    }

    private fun runBulk(
        retry: () -> Unit,
        operation: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            runCatchingApi {
                operation()
                productChoiceGroupApi.getAll().items
            }.onSuccess { links ->
                withData { it.copy(allProductGroupLinks = links) }
            }.onError(retry = retry) { uiError ->
                log.e { "bulk choice-group operation failed: $uiError" }
                bulkError = uiError
            }
        }
    }

    /**
     * The active Pfand types the product form offers.
     *
     * Tolerant on purpose: the list only feeds one optional dropdown, so a server that refuses or
     * lacks `/api/pfand-types` must not take the whole product catalogue down with it. An editor
     * opened on a product that already carries a link still shows that link by name.
     */
    private suspend fun loadPfandTypes(): List<PfandTypeDto.PfandTypeResponseDto> =
        try {
            pfandTypeApi.list()
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Throwable) {
            log.w(ex) { "load Pfand types failed — the product form offers none" }
            emptyList()
        }

    private suspend fun reloadProducts() {
        val fresh = productApi.getAllProducts()
        withData { it.copy(allProducts = fresh) }
    }

    private suspend fun reloadCategories() {
        val fresh = categoryApi.getAllProductCategories()
        withData { it.copy(categories = fresh) }
    }
}
