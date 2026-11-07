package org.example.project.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The decimal separator [PosTextField] displays in its [PosTextFieldType.Price] and
 * [PosTextFieldType.Percentage] fields, or null to show the value as it is.
 *
 * Opt-in per app: adminApp provides the operator's locale separator (`,` in German) at its root,
 * composeApp provides nothing and keeps showing the canonical dot. Display only — see
 * [DecimalSeparatorVisualTransformation].
 */
val LocalDecimalInputSeparator = staticCompositionLocalOf<Char?> { null }
