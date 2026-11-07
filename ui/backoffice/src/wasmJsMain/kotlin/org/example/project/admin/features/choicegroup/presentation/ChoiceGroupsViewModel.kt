package org.example.project.admin.features.choicegroup.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.example.project.core.data.remote.ChoiceGroupApiClient
import org.example.project.core.data.remote.ProductExtrasApiClient
import org.example.project.core.domain.dto.product.ChoiceGroupDto
import org.example.project.core.network.error.PosApiException
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.error.toUiError
import org.example.project.core.utils.toCanonicalMoneyOrNull
import org.example.project.core.utils.toEditableDecimal

class ChoiceGroupsViewModel(
    private val choiceGroupApi: ChoiceGroupApiClient,
    private val extrasApi: ProductExtrasApiClient,
) : ViewModel() {
    var state by mutableStateOf<ChoiceGroupsState>(ChoiceGroupsState.Loading)
        private set
    var editor by mutableStateOf<GroupEditor?>(null)
        private set

    /** The editor as opened: the pristine copy [isEditorDirty] compares against. */
    private var editorOriginal by mutableStateOf<GroupEditor?>(null)

    /** True when the open editor (including its option rows) differs from what it was opened with. */
    val isEditorDirty: Boolean get() = editor?.isDirtyComparedTo(editorOriginal) == true

    fun refresh() {
        state = ChoiceGroupsState.Loading
        viewModelScope.launch {
            runCatchingApi {
                coroutineScope {
                    val groups = choiceGroupApi.getAll().items
                    val extras = extrasApi.getAllProductExtras()
                    ChoiceGroupsState.Data(groups, extras)
                }
            }.onSuccess { state = it }
                .onError { uiError ->
                    state = ChoiceGroupsState.Error(uiError)
                }
        }
    }

    // ----- editor lifecycle -----
    fun openCreate() {
        editor = GroupEditor()
        editorOriginal = editor
    }

    fun openEdit(g: ChoiceGroupDto.ChoiceGroupResponseDto) {
        editor =
            GroupEditor(
                id = g.id,
                name = g.name,
                en = g.en ?: "",
                ch = g.ch ?: "",
                minSelections = g.minSelections.toString(),
                maxSelections = g.maxSelections.toString(),
                isActive = g.isActive,
                options = g.options.sortedBy { it.sortPosition }.map { OptionRow(it.optionId, it.name, it.priceDelta.toEditableDecimal(), it.isDefault) },
            )
        editorOriginal = editor
    }

    fun onField(block: (GroupEditor) -> GroupEditor) {
        editor = editor?.let { old -> block(old).withEditedErrorsCleared(old) }
    }

    fun closeEditor() {
        editor = null
        editorOriginal = null
    }

    // ----- option-row editing (extras the group doesn't already contain are the pool) -----
    private fun withOptions(block: (List<OptionRow>) -> List<OptionRow>) {
        editor = editor?.let { old -> old.copy(options = block(old.options)).withEditedErrorsCleared(old) }
    }

    fun addOption(extraId: Int) {
        val e = editor ?: return
        if (e.options.any { it.extraId == extraId }) return
        val name = (state as? ChoiceGroupsState.Data)?.extras?.firstOrNull { it.id == extraId }?.name ?: ""
        withOptions { it + OptionRow(extraId, name) }
    }

    fun removeOption(extraId: Int) = withOptions { rows -> rows.filterNot { it.extraId == extraId } }

    fun setOptionPriceDelta(
        extraId: Int,
        value: String,
    ) = withOptions { rows ->
        rows.map {
            if (it.extraId ==
                extraId
            ) {
                it.copy(priceDelta = value)
            } else {
                it
            }
        }
    }

    fun setOptionDefault(
        extraId: Int,
        value: Boolean,
    ) = withOptions { rows ->
        rows.map {
            if (it.extraId ==
                extraId
            ) {
                it.copy(isDefault = value)
            } else {
                it
            }
        }
    }

    fun moveOption(
        extraId: Int,
        up: Boolean,
    ) = withOptions { rows ->
        val idx = rows.indexOfFirst { it.extraId == extraId }
        val target = if (up) idx - 1 else idx + 1
        if (idx < 0 || target !in rows.indices) {
            rows
        } else {
            rows.toMutableList().apply { add(target, removeAt(idx)) }
        }
    }

    /** Extras not already used as an option in the current editor. */
    fun availableExtras(): List<org.example.project.core.domain.dto.product.ProductExtrasDto.ProductExtrasResponseDto> {
        val used = editor?.options?.map { it.extraId }?.toSet() ?: emptySet()
        // A deleted extra (soft, inactive) is no longer offered and must not be placed again (NOTE-c21).
        return (state as? ChoiceGroupsState.Data)?.extras.orEmpty().filter { it.isActive && it.id !in used }
    }

    fun saveGroup() {
        val e = editor ?: return
        val errors = validateGroupDraft(e)
        if (errors.isNotEmpty()) {
            editor = e.copy(fieldErrors = errors, validationAttempt = e.validationAttempt + 1, fieldError = null, saveError = null)
            return
        }
        val min = e.minSelectionsValue
        val max = e.maxSelectionsValue
        // Deltas go out canonical: a "1,50" still in a focused field must not reach the server raw.
        // Validation above already refused an unreadable delta, so the fallback is never taken.
        val deltas = e.options.map { row -> if (row.priceDelta.isBlank()) "0" else row.priceDelta.toCanonicalMoneyOrNull() ?: return }
        editor = e.copy(saving = true, fieldError = null, fieldErrors = emptyMap(), saveError = null)
        val options =
            e.options.mapIndexed { i, row ->
                ChoiceGroupDto.OptionRequestDto(
                    optionId = row.extraId,
                    priceDelta = deltas[i],
                    sortPosition = i,
                    isDefault = row.isDefault,
                )
            }
        viewModelScope.launch {
            try {
                if (e.isCreate) {
                    choiceGroupApi.create(
                        ChoiceGroupDto.CreateChoiceGroupRequestDto(
                            name = e.name,
                            en = e.en.ifBlank { null },
                            ch = e.ch.ifBlank { null },
                            minSelections = min,
                            maxSelections = max,
                            isActive = e.isActive,
                            options = options,
                        ),
                    )
                } else {
                    choiceGroupApi.update(
                        e.id!!,
                        ChoiceGroupDto.UpdateChoiceGroupRequestDto(
                            name = e.name,
                            en = e.en.ifBlank { null },
                            ch = e.ch.ifBlank { null },
                            minSelections = min,
                            maxSelections = max,
                            isActive = e.isActive,
                            options = options,
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

    fun delete(id: Int) {
        viewModelScope.launch {
            try {
                choiceGroupApi.delete(id)
                reload()
            } catch (
                ex: CancellationException,
            ) {
                throw ex
            } catch (ex: Throwable) {
                // keep Data on failure
            }
        }
    }

    private suspend fun reload() {
        val fresh = choiceGroupApi.getAll().items
        (state as? ChoiceGroupsState.Data)?.let { state = it.copy(groups = fresh) }
    }
}
