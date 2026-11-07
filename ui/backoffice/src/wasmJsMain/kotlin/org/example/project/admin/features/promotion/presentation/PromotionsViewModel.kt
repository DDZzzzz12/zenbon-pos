package org.example.project.admin.features.promotion.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.example.project.core.data.remote.ProductApiClient
import org.example.project.core.data.remote.PromotionApiClient
import org.example.project.core.domain.dto.promotion.PromotionDto
import org.example.project.core.network.error.PosApiException
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.utils.toCanonicalPriceOrNull
import org.example.project.core.utils.toEditableDecimal

/**
 * Promotion management for the Products toolbar modal.
 *
 * Adapted from composeApp's `PromotionViewModel`; the one substantive change is the product
 * source — adminApp has no `ProductDataProvider` (that wraps composeApp's SQLDelight cache, which
 * does not exist on wasm), so products come straight off [ProductApiClient].
 */
class PromotionsViewModel(
    private val promotionApi: PromotionApiClient,
    private val productApi: ProductApiClient,
) : ViewModel() {
    private val log = Logger.withTag("promotions")

    var state by mutableStateOf<PromotionsState>(PromotionsState.Loading)
        private set
    var editor by mutableStateOf<PromotionEditor?>(null)
        private set

    /** The editor as opened: the pristine copy [isEditorDirty] compares against. */
    private var editorOriginal by mutableStateOf<PromotionEditor?>(null)

    /** True when the open editor differs from what it was opened with; a clean form never prompts. */
    val isEditorDirty: Boolean get() = editor?.isDirtyComparedTo(editorOriginal) == true

    /** Driven by the manager dialog on open (the VM is retained app-wide). */
    fun refresh() {
        state = PromotionsState.Loading
        viewModelScope.launch {
            runCatchingApi {
                coroutineScope {
                    val promotions = promotionApi.getAllPromotions()
                    val products = productApi.getAllProducts(isActive = true)
                    PromotionsState.Data(promotions, products)
                }
            }.onSuccess { data ->
                log.d { "loaded ${data.promotions.size} promotions, ${data.products.size} products" }
                state = data
            }.onError { uiError ->
                log.e { "load promotions failed" }
                state = PromotionsState.Error(uiError)
            }
        }
    }

    fun clearActionError() = updateData { it.copy(actionError = null) }

    // ----- selection + usage -----
    fun select(id: Long?) {
        updateData { it.copy(selectedId = id, usage = null) }
        val target = id ?: return
        viewModelScope.launch {
            runCatchingApi { promotionApi.getPromotionUsage(target) }
                .onSuccess { usage -> updateData { if (it.selectedId == target) it.copy(usage = usage) else it } }
                .onError { uiError ->
                    log.e { "usage for promotion $target failed" }
                    updateData { it.copy(actionError = uiError) }
                }
        }
    }

    // ----- editor lifecycle -----
    fun openCreate() {
        editor = PromotionEditor()
        editorOriginal = editor
    }

    fun openEdit(p: PromotionDto.PromotionResponseDto) {
        editor =
            PromotionEditor(
                id = p.id,
                name = p.name,
                description = p.description ?: "",
                discountType = p.discountType,
                // admin20 MD-09: „2,50“ came back as „2.50“ / „2.5000“. Seed without DB-scale zeros;
                // the field's locale transformation shows the comma.
                discountValue = p.discountValue.toEditableDecimal(),
                scope = p.scope,
                targetProductId = p.targetProductId,
                targetProductName = p.targetProductName ?: "",
                isMultiUse = p.isMultiUse,
                combinable = p.combinable,
                validFromDate = datePartOf(p.validFrom),
                validUntilDate = p.validUntil?.let { datePartOf(it) } ?: "",
                isActive = p.isActive,
            )
        editorOriginal = editor
    }

    fun onField(block: (PromotionEditor) -> PromotionEditor) {
        editor = editor?.let { old -> block(old).withEditedErrorsCleared(old) }
    }

    fun closeEditor() {
        editor = null
        editorOriginal = null
    }

    fun save() {
        val e = editor ?: return
        val errors = validatePromotionDraft(e)
        if (errors.isNotEmpty()) {
            editor = e.copy(fieldErrors = errors, validationAttempt = e.validationAttempt + 1, fieldError = null)
            return
        }
        editor = e.copy(saving = true, fieldError = null, fieldErrors = emptyMap())
        viewModelScope.launch { persist(e) }
    }

    fun deactivate(id: Long) {
        viewModelScope.launch {
            runCatchingApi { promotionApi.deactivatePromotion(id) }
                .onSuccess {
                    log.d { "deactivated promotion $id" }
                    reload()
                }.onError { uiError ->
                    log.e { "deactivate promotion $id failed" }
                    updateData { it.copy(actionError = uiError) }
                }
        }
    }

    // ----- helpers -----

    private suspend fun persist(e: PromotionEditor) {
        try {
            if (e.isCreate) {
                promotionApi.createPromotion(
                    PromotionDto.CreatePromotionRequestDto(
                        name = e.name,
                        description = e.description.ifBlank { null },
                        discountType = e.discountType,
                        discountValue = e.discountValue.toCanonicalPriceOrNull() ?: e.discountValue,
                        scope = e.scope,
                        targetProductId = e.targetProductId,
                        isMultiUse = e.isMultiUse,
                        combinable = e.combinable,
                        validFrom = startOfDay(e.validFromDate),
                        validUntil = e.validUntilDate.ifBlank { null }?.let { endOfDay(it) },
                    ),
                )
            } else {
                // UpdatePromotionRequestDto deliberately carries no discountType/discountValue/
                // scope/targetProductId — the server does not accept a change to any of them.
                promotionApi.updatePromotion(
                    e.id!!,
                    PromotionDto.UpdatePromotionRequestDto(
                        name = e.name,
                        description = e.description.ifBlank { null },
                        isMultiUse = e.isMultiUse,
                        combinable = e.combinable,
                        validFrom = startOfDay(e.validFromDate),
                        validUntil = e.validUntilDate.ifBlank { null }?.let { endOfDay(it) },
                        isActive = e.isActive,
                    ),
                )
            }
            editor = null
            editorOriginal = null
            reload()
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: PosApiException) {
            log.e(ex) { "save promotion failed" }
            editor = editor?.copy(saving = false, fieldError = ex.message ?: "save")
        } catch (ex: Throwable) {
            log.e(ex) { "save promotion failed" }
            editor = editor?.copy(saving = false, fieldError = "save")
        }
    }

    /**
     * Refreshes the list after a mutation, carrying its own error handling.
     *
     * [runCatchingApi]'s `onSuccess` is plain `kotlin.Result.onSuccess` — it is inline and runs
     * *outside* the try block — so an unwrapped call here would escape into `viewModelScope`:
     * the user would see no error and a stale list still showing the mutated promotion.
     */
    private suspend fun reload() {
        runCatchingApi { promotionApi.getAllPromotions() }
            .onSuccess { fresh -> updateData { it.copy(promotions = fresh) } }
            .onError { uiError ->
                log.e { "reload promotions failed" }
                updateData { it.copy(actionError = uiError) }
            }
    }

    private fun updateData(block: (PromotionsState.Data) -> PromotionsState.Data) {
        (state as? PromotionsState.Data)?.let { state = block(it) }
    }
}
