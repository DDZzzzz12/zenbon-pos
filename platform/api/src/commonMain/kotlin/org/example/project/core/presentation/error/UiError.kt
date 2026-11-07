package org.example.project.core.presentation.error

import org.example.project.core.error.FieldError
import org.example.project.core.error.PosErrorCode
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.CommonKeys

/**
 * Typed UI-side error model shared by composeApp and adminApp. Held in ViewModel state
 * in place of the legacy String-based error. Carries enough info for
 * [org.example.project.ui.components.PosErrorWatcher] to display a localized rich dialog (PosErrorDialog).
 */
sealed class UiError {
    abstract val code: PosErrorCode?
    abstract val traceId: String?
    abstract val titleKey: StringKey
    abstract val messageKey: StringKey
    abstract val messageArgs: List<Any>

    data class FromServer(
        override val code: PosErrorCode,
        override val traceId: String?,
        override val titleKey: StringKey,
        override val messageKey: StringKey,
        override val messageArgs: List<Any> = emptyList(),
        val httpStatus: Int,
        val fieldErrors: List<FieldError> = emptyList(),
        val rawDetail: String? = null,
    ) : UiError()

    data class Network(
        override val traceId: String? = null,
        override val titleKey: StringKey = CommonKeys.networkErrorTitle,
        override val messageKey: StringKey = CommonKeys.networkErrorMessage,
        override val messageArgs: List<Any> = emptyList(),
        override val code: PosErrorCode? = null,
        val retry: (() -> Unit)? = null,
    ) : UiError()

    data class Unknown(
        override val traceId: String? = null,
        override val titleKey: StringKey = CommonKeys.unknownErrorTitle,
        override val messageKey: StringKey = CommonKeys.unknownErrorMessage,
        override val messageArgs: List<Any> = emptyList(),
        override val code: PosErrorCode? = null,
        val cause: Throwable? = null,
    ) : UiError()
}
