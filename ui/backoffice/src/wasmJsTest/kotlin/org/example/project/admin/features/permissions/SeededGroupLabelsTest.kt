package org.example.project.admin.features.permissions

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.core.presentation.strings.AdminTranslations
import org.example.project.core.auth.CapabilityBundles
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.utils.enumStrings.localizedTseModeName
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * QA admin19 NOTE-c: the permission-group editor showed the server's English name/description for a
 * seeded group ("Accountant" beside a list reading "Buchhaltung"), and the TSE card a raw "DISABLED".
 */
class SeededGroupLabelsTest {
    @BeforeTest
    fun setUp() = AdminTranslations.init()

    @Test
    fun everySeededGroup_hasATranslatedNameAndDescription() {
        CapabilityBundles.definitions().forEach { definition ->
            val name = assertNotNull(AdminStringKeys.permissionGroupName(definition.code), "name key for ${definition.code}")
            val description =
                assertNotNull(AdminStringKeys.permissionGroupDescription(definition.code), "description key for ${definition.code}")
            Language.entries.forEach { language ->
                assertNotEquals(name.id, TranslationRegistry.get(name, language), "$language name of ${definition.code}")
                assertNotEquals(description.id, TranslationRegistry.get(description, language), "$language description of ${definition.code}")
            }
        }
    }

    @Test
    fun germanSeededDescription_isNotTheServersEnglish() {
        val accountant = CapabilityBundles.definitions().first { it.code == "ACCOUNTANT" }
        val key = assertNotNull(AdminStringKeys.permissionGroupDescription("ACCOUNTANT"))
        assertNotEquals(accountant.description, TranslationRegistry.get(key, Language.GERMAN))
    }

    @Test
    fun customGroup_hasNoTranslation() {
        assertEquals(null, AdminStringKeys.permissionGroupDescription("QA_CUSTOM"))
    }

    @Test
    fun tseMode_isLocalized_andUnknownPassesThrough() {
        assertNotEquals("DISABLED", localizedTseModeName("DISABLED", Language.GERMAN))
        assertEquals("SOMETHING_NEW", localizedTseModeName("SOMETHING_NEW", Language.GERMAN))
    }
}
