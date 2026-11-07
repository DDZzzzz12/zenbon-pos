package org.example.project.admin.core.presentation.strings

import org.example.project.core.data.database.KeyValueStore
import org.example.project.core.domain.enums.setup.Language
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AdminLanguagePreferenceTest {
    private class MapStore : KeyValueStore {
        val map = mutableMapOf<String, String>()

        override fun getString(key: String): String? = map[key]

        override fun setString(
            key: String,
            value: String,
        ) {
            map[key] = value
        }

        override fun remove(key: String) {
            map.remove(key)
        }

        override fun getAll(): Map<String, String> = map.toMap()

        override fun clear() = map.clear()
    }

    private val original = AdminLocale.language

    @AfterTest
    fun restore() {
        AdminLocale.language = original
    }

    @Test
    fun selecting_a_language_switches_the_locale_and_persists_it() {
        val store = MapStore()
        val target = Language.entries.first { it != original }

        selectAdminLanguage(store, target)

        assertEquals(target, AdminLocale.language)
        assertEquals(target.code, store.map[KEY_LANGUAGE])
        assertEquals(target, storedAdminLanguage(store))
    }

    @Test
    fun nothing_or_an_unknown_code_stored_is_null() {
        val store = MapStore()
        assertNull(storedAdminLanguage(store))
        store.setString(KEY_LANGUAGE, "xx")
        assertNull(storedAdminLanguage(store))
    }
}
