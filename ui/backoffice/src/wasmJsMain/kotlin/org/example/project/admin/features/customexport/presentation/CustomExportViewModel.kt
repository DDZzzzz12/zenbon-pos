package org.example.project.admin.features.customexport.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.launch
import org.example.project.core.data.remote.CustomExportApiClient
import org.example.project.core.domain.dto.customexport.CustomExportDto
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.utils.FileSaver

/**
 * Drives the custom export builder: pick a subject, tick columns, rename them, save, download.
 *
 * The catalogue is whatever the server handed this caller — it is already filtered to their
 * capabilities, so nothing here decides what may be offered. Column order is the draft's list order
 * and nothing else; positions are minted at save time from the index, which is why the draft cannot
 * produce the duplicate positions the server rejects.
 */
class CustomExportViewModel(
    private val apiClient: CustomExportApiClient,
) : ViewModel() {
    private val log = Logger.withTag("CustomExport")

    var state by mutableStateOf(CustomExportState())
        private set

    fun clearError() {
        state = state.copy(error = null)
    }

    /**
     * Loads the catalogue and the saved definitions.
     *
     * Deliberately not called from `init`: adminApp shares one `ViewModelStoreOwner`, so this view
     * model survives a logout and an `init` load would show the previous session's data after the
     * next login. The screen calls this from a `LaunchedEffect`.
     */
    fun load() {
        state = state.copy(isLoading = true, error = null)
        viewModelScope.launch {
            runCatchingApi {
                apiClient.catalogue() to apiClient.list()
            }.onSuccess { (subjects, definitions) ->
                log.d { "custom export: ${subjects.size} subject(s), ${definitions.size} definition(s)" }
                state = state.copy(isLoading = false, subjects = subjects, definitions = definitions)
            }.onError(retry = { load() }) { uiError ->
                log.e { "custom export load failed" }
                state = state.copy(isLoading = false, error = uiError)
            }
        }
    }

    /** Opens an empty draft, optionally pre-pointed at a subject. */
    fun startDraft(subjectKey: String = "") {
        val fresh = DefinitionDraft(subjectKey = subjectKey)
        state = state.copy(draft = fresh, baseline = fresh, selectedId = null, truncatedRowCount = null)
    }

    /**
     * Opens a saved definition for editing.
     *
     * The server already sorted the columns by position, so the list index the draft relies on is
     * simply the order they arrived in — no re-sort here.
     *
     * A key the catalogue no longer knows is kept as a row **and** named in
     * [DefinitionDraft.unresolvedKeys]. Dropping it would leave the operator told to fix a field the
     * editor cannot show them, and a silent re-save would quietly discard the column.
     */
    fun editDefinition(id: Long) {
        val definition = state.definitions.firstOrNull { it.id == id } ?: return
        val opened =
            DefinitionDraft(
                id = definition.id,
                name = definition.name,
                subjectKey = definition.subjectKey,
                // Display only: a raw-key or blank stored heading is shown as the catalogue label, but the
                // stored definition is untouched until the operator saves (see [defaultHeading]).
                fields = displayedFields(definition, state.subjects.firstOrNull { it.key == definition.subjectKey }),
                dateFrom = definition.dateFrom,
                dateTo = definition.dateTo,
                enabledToggles = definition.enabledToggles.toSet(),
                unresolvedKeys = definition.unknownKeys.toSet(),
            )
        state = state.copy(selectedId = id, truncatedRowCount = null, draft = opened, baseline = opened)
    }

    fun closeDraft() {
        state = state.copy(draft = null, baseline = null, selectedId = null)
    }

    fun updateName(name: String) = updateDraft { it.copy(name = name) }

    /** Switching subject drops the chosen columns and toggles: they belong to the subject left behind. */
    fun selectSubject(subjectKey: String) =
        updateDraft { draft ->
            if (draft.subjectKey == subjectKey) {
                draft
            } else {
                draft.copy(subjectKey = subjectKey, fields = emptyList(), enabledToggles = emptySet())
            }
        }

    /**
     * Ticks or un-ticks a column.
     *
     * A newly ticked column takes the catalogue's own label as its heading, so an operator who never
     * opens the heading field still gets a readable column rather than a raw key like `partner.vat_id`.
     */
    fun toggleField(fieldKey: String) {
        val catalogueFields = state.draftSubject?.fields.orEmpty()
        val catalogueField = catalogueFields.firstOrNull { it.key == fieldKey }
        val defaultHeading = catalogueField?.label ?: fieldKey
        updateDraft { draft ->
            if (draft.fields.any { it.fieldKey == fieldKey }) {
                draft.copy(fields = draft.fields.filterNot { it.fieldKey == fieldKey })
            } else {
                draft.copy(fields = draft.fields + DraftField(fieldKey, defaultHeading))
            }
        }
    }

    fun updateHeading(
        fieldKey: String,
        heading: String,
    ) = updateDraft { draft ->
        draft.copy(fields = draft.fields.map { if (it.fieldKey == fieldKey) it.copy(heading = heading) else it })
    }

    /** Moves a column one place left. Moving the first one is a no-op, not an error. */
    fun moveFieldUp(index: Int) = swapFields(index, index - 1)

    /** Moves a column one place right. Moving the last one is a no-op, not an error. */
    fun moveFieldDown(index: Int) = swapFields(index, index + 1)

    /**
     * Sets the export's date range from two **plain `yyyy-MM-dd` dates**.
     *
     * The wire carries an ISO *date-time* and the server compares both bounds **inclusively**
     * (`>=` / `<=`), so the time halves decide what the file contains. They are minted here, from the
     * date, rather than by the caller:
     *
     * - the lower bound opens its day (`T00:00:00`),
     * - the upper bound **closes** its day (`T23:59:59`).
     *
     * An upper bound left at midnight would be a half-open range: every row booked after 00:00 on the
     * last day the operator asked for is dropped, the file still looks complete, and the loss surfaces
     * weeks later as totals that do not reconcile. That is a data-correctness rule, not formatting,
     * which is why it lives here and not in a screen — a second caller passing a bare date cannot get
     * it wrong.
     *
     * A value that already carries a time is re-cut at the `T`, so passing a stored bound back in is
     * idempotent rather than doubly suffixed. A blank or null date means *no bound* and is stored as
     * null: an empty string is not a date, and the server would reject it.
     */
    fun setDateRange(
        fromDate: String?,
        toDate: String?,
    ) = updateDraft {
        it.copy(
            dateFrom = fromDate.toDateBound(START_OF_DAY),
            dateTo = toDate.toDateBound(END_OF_DAY),
        )
    }

    fun toggleFilter(toggleKey: String) =
        updateDraft { draft ->
            val toggles =
                if (toggleKey in draft.enabledToggles) {
                    draft.enabledToggles - toggleKey
                } else {
                    draft.enabledToggles + toggleKey
                }
            draft.copy(enabledToggles = toggles)
        }

    /**
     * Saves the draft — as an update of the definition it was opened from, or as a new one.
     *
     * Positions are the list indices, 0..n-1. A draft with no columns is not sent: the server rejects
     * it anyway, and a round trip to be told what the client already knows is a worse experience than
     * a disabled button.
     */
    fun save() {
        val draft = state.draft ?: return
        if (draft.fields.isEmpty()) {
            log.d { "save skipped: the draft has no columns" }
            return
        }
        val request =
            CustomExportDto.SaveDefinitionRequestDto(
                name = draft.name,
                subjectKey = draft.subjectKey,
                fields =
                    draft.fields.mapIndexed { index, field ->
                        CustomExportDto.DefinitionFieldDto(
                            fieldKey = field.fieldKey,
                            position = index,
                            heading = field.heading,
                        )
                    },
                dateFrom = draft.dateFrom,
                dateTo = draft.dateTo,
                enabledToggles = draft.enabledToggles.toList(),
            )
        state = state.copy(isSaving = true, error = null)
        // A draft opened from a saved definition carries its id and is written back in place; only a
        // new draft is POSTed. Saving an edit through `save` stored a second copy beside the original.
        val existingId = draft.id
        viewModelScope.launch {
            runCatchingApi { if (existingId != null) apiClient.update(existingId, request) else apiClient.save(request) }
                .onSuccess { saved ->
                    log.i { "custom export definition ${saved.id} saved with ${saved.fields.size} column(s)" }
                    state = state.copy(isSaving = false, draft = null, baseline = null, selectedId = null)
                    load()
                }.onError { uiError ->
                    log.e { "custom export save failed for subject ${draft.subjectKey}" }
                    state = state.copy(isSaving = false, error = uiError)
                }
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            runCatchingApi { apiClient.delete(id) }
                .onSuccess {
                    log.i { "custom export definition $id deleted" }
                    val stillOpen = state.draft?.id == id
                    state =
                        state.copy(
                            draft = if (stillOpen) null else state.draft,
                            baseline = if (stillOpen) null else state.baseline,
                            selectedId = null,
                        )
                    load()
                }.onError { uiError ->
                    log.e { "custom export delete failed for definition $id" }
                    state = state.copy(error = uiError)
                }
        }
    }

    /**
     * Runs a saved definition and hands the CSV to the browser.
     *
     * A truncated run is not a failure — the file is still useful — but the row count is kept so the
     * screen can say the file is short. An operator who is not told reconciles against a silently
     * incomplete list.
     */
    fun run(id: Long) {
        state = state.copy(isRunning = true, truncatedRowCount = null, error = null)
        viewModelScope.launch {
            runCatchingApi { apiClient.run(id) }
                .onSuccess { result ->
                    log.i { "custom export $id ran: ${result.rowCount} row(s), truncated=${result.truncated}" }
                    FileSaver.saveFile(
                        data = result.download.bytes,
                        filename = result.download.fileName ?: "export.csv",
                        mimeType = "text/csv",
                    )
                    state =
                        state.copy(
                            isRunning = false,
                            truncatedRowCount = if (result.truncated) result.rowCount else null,
                        )
                }.onError { uiError ->
                    log.e { "custom export run failed for definition $id" }
                    state = state.copy(isRunning = false, error = uiError)
                }
        }
    }

    /** Applies [transform] to the open draft, or does nothing when none is open. */
    private inline fun updateDraft(transform: (DefinitionDraft) -> DefinitionDraft) {
        val draft = state.draft ?: return
        state = state.copy(draft = transform(draft))
    }

    /** Swaps two columns, ignoring an index that would fall off either end. */
    private fun swapFields(
        from: Int,
        to: Int,
    ) = updateDraft { draft ->
        if (from !in draft.fields.indices || to !in draft.fields.indices) {
            draft
        } else {
            val fields = draft.fields.toMutableList()
            fields[from] = draft.fields[to]
            fields[to] = draft.fields[from]
            draft.copy(fields = fields)
        }
    }

    private companion object {
        /** Opens the lower bound's day. */
        const val START_OF_DAY = "T00:00:00"

        /**
         * Closes the upper bound's day. The server's comparison is inclusive, so this is the second
         * that keeps the final day *in* the export. Do not "tidy" it to midnight.
         */
        const val END_OF_DAY = "T23:59:59"

        /** A date plus its time half, or null when there is no date to bound with. */
        private fun String?.toDateBound(time: String): String? {
            val date = this?.substringBefore('T')?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return date + time
        }
    }
}
