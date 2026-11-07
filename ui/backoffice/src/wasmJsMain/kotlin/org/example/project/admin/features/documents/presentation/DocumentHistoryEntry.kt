package org.example.project.admin.features.documents.presentation

/** One step of a Beleg's Verlauf: reached ([done]) or the one waiting next ([current]); neither = later. */
data class DocumentHistoryEntry(
    val step: DocumentHistoryStep,
    val done: Boolean,
    val current: Boolean,
)
