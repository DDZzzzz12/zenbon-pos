package org.example.project.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The [PosDialogPhoneMode] a [PosDialog] uses when its caller does not name one.
 *
 * An app-level default rather than a per-call-site argument, like [LocalPosTextFieldAppearance]:
 * `PosDialog` has ~130 call sites across both apps. The default is [PosDialogPhoneMode.Compact], so
 * an app that provides nothing (composeApp, the till) keeps the centred card at every width. adminApp
 * provides [PosDialogPhoneMode.Auto] in its theme, where form-like dialogs fill the screen on a phone.
 */
val LocalPosDialogPhoneDefault =
    staticCompositionLocalOf { PosDialogPhoneMode.Compact }
