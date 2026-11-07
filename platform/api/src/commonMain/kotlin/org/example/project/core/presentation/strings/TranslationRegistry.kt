package org.example.project.core.presentation.strings

import org.example.project.core.domain.enums.setup.Language

/**
 * Runtime registry mapping [StringKey]s to translated text per [Language].
 *
 * Compose-free by design so it can live in `shared/` and be consumed by both
 * the server (never) and every client UI module. Each UI module initializes it
 * once at startup with that module's translation maps.
 *
 * @since 1.0.0
 */
object TranslationRegistry {
    private lateinit var translations: Map<Language, Map<StringKey, String>>

    fun initialize(translationMaps: Map<Language, Map<StringKey, String>>) {
        translations = translationMaps
    }

    /**
     * Whether [initialize] has run in this process. [get] throws until it has, so an entry point
     * that can run without the UI (an Android service restarted by the system after a crash)
     * checks this rather than assuming the UI module's startup already initialized the registry.
     */
    val isInitialized: Boolean
        get() = ::translations.isInitialized

    fun get(
        key: StringKey,
        language: Language,
    ): String = translations[language]?.get(key) ?: key.id

    fun findMissingTranslations(): Map<Language, List<StringKey>> {
        if (!::translations.isInitialized) return emptyMap()
        val allKeys = translations.values.flatMap { it.keys }.toSet()
        return Language.entries
            .associateWith { lang ->
                allKeys.filter { key -> translations[lang]?.containsKey(key) != true }
            }.filterValues { it.isNotEmpty() }
    }
}
