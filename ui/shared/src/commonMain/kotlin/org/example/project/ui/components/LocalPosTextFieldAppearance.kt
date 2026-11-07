package org.example.project.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The [PosTextFieldAppearance] a [PosTextField] uses when its caller does not name one.
 *
 * An app-level default rather than a per-call-site argument: `PosTextField` has ~270 usages across
 * both apps, so choosing a look by editing every call is churn that goes stale the moment one is
 * added. A theme provides this once and every field below it follows.
 *
 * It exists because the two apps genuinely want different answers. adminApp is a back-office
 * console on a mouse-driven screen and reads better flat. composeApp is the till: its forms take
 * prices and quantities through an on-screen keyboard, where an outlined box with a floating label
 * gives a bigger, more explicit target — so it keeps [PosTextFieldAppearance.Outlined].
 *
 * `staticCompositionLocalOf` because a theme sets it once at the root; a change recomposes
 * everything beneath, which is exactly what switching a design language should do.
 */
val LocalPosTextFieldAppearance =
    staticCompositionLocalOf { PosTextFieldAppearance.Outlined }
