package org.example.project.admin.features.extras.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.example.project.core.data.remote.ChoiceGroupApiClient
import org.example.project.core.data.remote.ProductExtrasApiClient
import org.example.project.core.domain.dto.product.ChoiceGroupDto
import org.example.project.core.domain.dto.product.ProductExtrasDto
import org.example.project.core.network.error.PosApiException
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.error.toUiError
import org.example.project.core.utils.toCanonicalPriceOrNull
import org.example.project.core.utils.toEditableDecimal

class ExtrasViewModel(
    private val extrasApi: ProductExtrasApiClient,
    private val choiceGroupApi: ChoiceGroupApiClient,
) : ViewModel() {
    private val log = Logger.withTag("extras")

    var state by mutableStateOf<ExtrasState>(ExtrasState.Loading)
        private set
    var editor by mutableStateOf<ExtraEditor?>(null)
        private set

    /** The editor as opened: the pristine copy [isEditorDirty] compares against. */
    private var editorOriginal by mutableStateOf<ExtraEditor?>(null)

    /** True when the open editor differs from what it was opened with; a clean form never prompts. */
    val isEditorDirty: Boolean get() = editor?.isDirtyComparedTo(editorOriginal) == true

    /** The last refused delete, shown in the manager dialog (it used to be only logged). */
    var deleteError by mutableStateOf<UiError?>(null)
        private set

    /** Driven by the manager dialog on open (VM is retained app-wide). */
    fun refresh() {
        state = ExtrasState.Loading
        viewModelScope.launch {
            runCatchingApi { extrasApi.getAllProductExtras() }
                .onSuccess { extras -> state = ExtrasState.Data(extras, loadGroups()) }
                .onError { uiError ->
                    log.e { "load extras failed" }
                    state = ExtrasState.Error(uiError)
                }
        }
    }

    fun openCreate() {
        editor = ExtraEditor()
        editorOriginal = editor
    }

    fun openEdit(e: ProductExtrasDto.ProductExtrasResponseDto) {
        editor =
            ExtraEditor(
                id = e.id,
                name = e.name,
                en = e.en ?: "",
                ch = e.ch ?: "",
                description = e.description ?: "",
                price = e.price.toEditableDecimal(),
                isActive = e.isActive,
            )
        editorOriginal = editor
    }

    fun onField(block: (ExtraEditor) -> ExtraEditor) {
        editor = editor?.let { old -> block(old).withEditedErrorsCleared(old) }
    }

    fun closeEditor() {
        editor = null
        editorOriginal = null
    }

    fun save() {
        val e = editor ?: return
        val errors = validateExtraDraft(e)
        if (errors.isNotEmpty()) {
            editor = e.copy(fieldErrors = errors, validationAttempt = e.validationAttempt + 1, fieldError = null, saveError = null)
            return
        }
        val price = if (e.price.isBlank()) "0" else e.price.toCanonicalPriceOrNull() ?: return
        if (!e.isActive && e.id != null && wasActive(e.id) && requiredGroupsNeeding(e.id).isNotEmpty()) {
            editor = e.copy(fieldError = "required_group", fieldErrors = emptyMap(), saveError = null)
            return
        }
        editor = e.copy(saving = true, fieldError = null, fieldErrors = emptyMap(), saveError = null)
        viewModelScope.launch {
            try {
                if (e.isCreate) {
                    extrasApi.createProductExtra(
                        ProductExtrasDto.CreateProductExtrasRequestDto(
                            name = e.name,
                            en = e.en.ifBlank { null },
                            ch = e.ch.ifBlank { null },
                            description = e.description.ifBlank { null },
                            price = price,
                            isActive = e.isActive,
                        ),
                    )
                } else {
                    extrasApi.updateProductExtra(
                        e.id!!.toLong(),
                        ProductExtrasDto.UpdateProductExtrasRequestDto(
                            name = e.name,
                            en = e.en.ifBlank { null },
                            ch = e.ch.ifBlank { null },
                            description = e.description.ifBlank { null },
                            price = price,
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
                editor = editor?.copy(saving = false, saveError = ex.toUiError())
            } catch (ex: Throwable) {
                editor = editor?.copy(saving = false, fieldError = "save")
            }
        }
    }

    /** See [ExtrasState.Data.requiredGroupsNeeding]; empty while not loaded. */
    fun requiredGroupsNeeding(extraId: Int): List<String> = (state as? ExtrasState.Data)?.requiredGroupsNeeding(extraId).orEmpty()

    fun dismissDeleteError() {
        deleteError = null
    }

    fun delete(id: Int) {
        // Client half of the NOTE-c21 guard: never send a delete the server will refuse with 409.
        if (requiredGroupsNeeding(id).isNotEmpty()) {
            log.w { "delete refused locally (extra $id): a required choice group needs it" }
            return
        }
        deleteError = null
        viewModelScope.launch {
            try {
                extrasApi.deleteProductExtra(id.toLong())
                reload()
            } catch (
                ex: CancellationException,
            ) {
                throw ex
            } catch (ex: PosApiException) {
                deleteError = ex.toUiError()
            } catch (ex: Throwable) {
                log.e(ex) { "delete extra $id failed — keeping current data" }
                deleteError = ex.toUiError()
            }
        }
    }

    private fun wasActive(id: Int): Boolean = (state as? ExtrasState.Data)?.extras?.firstOrNull { it.id == id }?.isActive ?: false

    /** Groups for the guard; a failure leaves the server's 409 as the only guard rather than failing the screen. */
    private suspend fun loadGroups(): List<ChoiceGroupDto.ChoiceGroupResponseDto> =
        try {
            choiceGroupApi.getAll().items
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Throwable) {
            log.w { "load choice groups failed — extra delete guard falls back to the server" }
            emptyList()
        }

    private suspend fun reload() {
        val fresh = extrasApi.getAllProductExtras()
        val groups = loadGroups()
        (state as? ExtrasState.Data)?.let { state = it.copy(extras = fresh, groups = groups) }
    }
}
