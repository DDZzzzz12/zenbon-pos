package org.example.project.features.financialdocuments.presentation.state

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class FinancialDocumentsEventBus {
    private val _events = MutableSharedFlow<FinancialDocumentsEvent>()
    val events = _events.asSharedFlow()

    suspend fun emit(event: FinancialDocumentsEvent) {
        _events.emit(event)
    }
}

sealed interface FinancialDocumentsEvent {
    /** Upload completed — documents list should refresh */
    data class UploadCompleted(
        val fileType: String,
        val documentId: Long,
    ) : FinancialDocumentsEvent

    /** A document was linked to or unlinked from a Kassenbuch entry — reload the cash book */
    data object LinkingCompleted : FinancialDocumentsEvent

    /** Kassenbuch entry created — kassenbuch should refresh */
    data object KassenbuchEntryCreated : FinancialDocumentsEvent

    /** Show upload dialog (triggered when attaching a Beleg to a Kassenbuch entry) */
    data object ShowUploadDialog : FinancialDocumentsEvent
}
