package org.example.project.core.presentation.strings

/**
 * Type-safe identifier for a localizable string.
 *
 * Shared across all UI modules (composeApp, adminApp) so that string keys
 * and their translations live in a single source of truth. The Compose-aware
 * `str()` accessor lives per UI module; only the key type and registry are shared.
 *
 * @property id Stable string identifier used as the translation-map key.
 * @since 1.0.0
 */
data class StringKey(
    val id: String,
)
