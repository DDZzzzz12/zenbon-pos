package org.example.project.core.presentation.components.docUtil

/** Where a document panel's file stands: still loading, loaded, or failed (with the failure's own message, if any). */
internal sealed interface DocumentFileLoad {
    data object Loading : DocumentFileLoad

    class Loaded(
        val bytes: ByteArray,
    ) : DocumentFileLoad

    class Failed(
        val reason: String?,
    ) : DocumentFileLoad
}
