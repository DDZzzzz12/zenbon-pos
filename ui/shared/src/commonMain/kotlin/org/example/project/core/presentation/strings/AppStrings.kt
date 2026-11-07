package org.example.project.core.presentation.strings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import org.example.project.core.domain.enums.setup.Language

/**
 * Current UI language for shared composables.
 *
 * Each host app provides it at its composition root: composeApp from its
 * LanguageManager, adminApp from its own language state. Reading it inside a
 * composable makes text reactive to language changes.
 */
val LocalAppLanguage = compositionLocalOf { Language.DEFAULT }

/**
 * Resolve a [StringKey] for the current [LocalAppLanguage]. The Compose-facing
 * counterpart to [getString]; shared by composeApp and adminApp.
 */
@Composable
fun str(key: StringKey): String = TranslationRegistry.get(key, LocalAppLanguage.current)

/**
 * Resolve a [StringKey] and substitute positional `{0}`, `{1}`… placeholders.
 */
@Composable
fun str(
    key: StringKey,
    vararg args: Any,
): String {
    val template = TranslationRegistry.get(key, LocalAppLanguage.current)
    return args.foldIndexed(template) { index, acc, arg ->
        acc.replace("{$index}", arg.toString())
    }
}
