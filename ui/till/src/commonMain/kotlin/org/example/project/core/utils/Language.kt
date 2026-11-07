package org.example.project.core.utils

import androidx.compose.runtime.*
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations

/**
 * Language manager for handling locale changes and persistence.
 */
class LanguageManager {
    private val _currentLanguage = mutableStateOf(Language.DEFAULT)
    val currentLanguage: State<Language> = _currentLanguage

    fun setLanguage(language: Language) {
        _currentLanguage.value = language
    }

    fun getCurrentLanguage(): Language = _currentLanguage.value
}

/**
 * CompositionLocal for providing language context throughout the app.
 */
val LocalLanguageManager =
    compositionLocalOf<LanguageManager> {
        error("LanguageManager not provided")
    }

@Composable
fun rememberLanguage(): State<Language> {
    val languageManager = LocalLanguageManager.current
    return languageManager.currentLanguage
}

/**
 * Provider composable for language context.
 * Provides LanguageManager throughout the composition tree.
 */
@Composable
fun LanguageProvider(
    initialLanguage: Language = Language.DEFAULT,
    content: @Composable () -> Unit,
) {
    val languageManager =
        remember {
            LanguageManager().apply { setLanguage(initialLanguage) }
        }

    remember {
        initializeAppTranslations()
        true
    }

    CompositionLocalProvider(
        LocalLanguageManager provides languageManager,
        LocalAppLanguage provides languageManager.currentLanguage.value,
    ) {
        content()
    }
}

/**
 * Initializes [TranslationRegistry] with composeApp's translations, once per process.
 *
 * Idempotent, and safe to call from every entry point. It must not rely on the Activity: after a
 * crash Android restarts the sticky `PosNotificationService` in a fresh process with no Activity,
 * and its foreground notification resolves localized text before any Compose tree exists —
 * without this, that restart crashed a second time on the uninitialized `lateinit` registry.
 */
fun initializeAppTranslations() {
    if (TranslationRegistry.isInitialized) return
    TranslationRegistry.initialize(defaultTranslations())

    val missing = TranslationRegistry.findMissingTranslations()
    missing.forEach { (lang, keys) ->
        println("WARNING: Missing ${keys.size} translations for ${lang.code}: ${keys.take(5).map { it.id }}")
    }
}
