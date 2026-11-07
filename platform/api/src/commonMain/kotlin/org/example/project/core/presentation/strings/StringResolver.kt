package org.example.project.core.presentation.strings

import org.example.project.core.domain.enums.setup.Language

/**
 * Non-Composable string resolution against the shared [TranslationRegistry].
 * Compose-free so it can live in `shared` and be used by ViewModels, enum helpers,
 * and any non-UI code across all clients.
 */
fun getString(
    key: StringKey,
    language: Language,
): String = TranslationRegistry.get(key, language)

/**
 * Resolve [key] for [language] and substitute positional `{0}`, `{1}`… placeholders.
 */
fun getString(
    key: StringKey,
    language: Language,
    vararg args: Any,
): String {
    val template = TranslationRegistry.get(key, language)
    return args.foldIndexed(template) { index, acc, arg ->
        acc.replace("{$index}", arg.toString())
    }
}
