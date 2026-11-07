package org.example.project.admin.core.presentation.strings

import org.example.project.core.data.database.KeyValueStore
import org.example.project.core.domain.enums.setup.Language

/** Persisted language choice; read back above the session gate so the login screen honours it. */
const val KEY_LANGUAGE = "admin.language"

/** Switches the live language and remembers it, so a reload does not silently revert to German. */
fun selectAdminLanguage(
    store: KeyValueStore,
    language: Language,
) {
    AdminLocale.language = language
    store.setString(KEY_LANGUAGE, language.code)
}

/** The language saved by [selectAdminLanguage], or null when none (or an unknown code) is stored. */
fun storedAdminLanguage(store: KeyValueStore): Language? {
    val code = store.getString(KEY_LANGUAGE) ?: return null
    return Language.entries.firstOrNull { it.code == code }
}
