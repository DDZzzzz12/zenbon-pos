package org.example.project.admin.features.documents.presentation.capture.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.PosTextFieldType

/**
 * One capture field: a [PosTextField] whose error state comes from the state's
 * `Map<InvoiceCaptureField, StringKey>` rather than being recomputed in the composable.
 *
 * That is the difference from `MoneyField` in the legacy editor, which derives `isError` from the raw
 * string on every recomposition and therefore cannot express a required-field or a cross-field rule.
 * Here the ViewModel owns the verdict and the composable only renders it.
 */
@Composable
internal fun CaptureTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    errorKey: StringKey?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    type: PosTextFieldType = PosTextFieldType.Default,
    placeholder: String? = null,
    suffix: String? = null,
) {
    PosTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier,
        // A locked field is read-only, not disabled: disabled draws grey-on-grey at 2.27:1 contrast.
        enabled = true,
        readOnly = !enabled,
        locked = !enabled,
        placeholder = placeholder,
        textFieldType = type,
        suffix = suffix?.let { unit -> { Text(unit) } },
        isError = errorKey != null,
        supportingText = errorKey?.let { key -> { Text(str(key)) } },
    )
}
