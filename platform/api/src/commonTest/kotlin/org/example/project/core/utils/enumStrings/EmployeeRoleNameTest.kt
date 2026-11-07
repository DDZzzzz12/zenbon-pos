package org.example.project.core.utils.enumStrings

import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** NOTE-22 (admin18): the staff directory badge printed the raw role constant ("CASHIER"). */
class EmployeeRoleNameTest {
    @BeforeTest
    fun initializeTranslations() {
        TranslationRegistry.initialize(defaultTranslations())
    }

    @Test
    fun knownRoles_areLocalized() {
        listOf("CASHIER", "MANAGER", "ADMIN", "TERMINAL_DEVICE").forEach { role ->
            Language.entries.forEach { language ->
                val label = localizedEmployeeRoleName(role, language)
                assertNotEquals(role, label, "$role in $language")
            }
        }
    }

    @Test
    fun legacyAlias_mapsToItsRole() {
        assertEquals(
            localizedEmployeeRoleName("TERMINAL_DEVICE", Language.GERMAN),
            localizedEmployeeRoleName("BASIC_EMPLOYEE", Language.GERMAN),
        )
    }

    @Test
    fun unknownRole_isShownAsSent() {
        assertEquals("AUDITOR", localizedEmployeeRoleName("AUDITOR", Language.GERMAN))
    }
}
