package org.example.project.admin.features.settings.presentation

import org.example.project.admin.core.presentation.navigation.SettingsRoute
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsRouteSectionTest {
    @Test
    fun everySectionRoundTrips() {
        SettingsSection.entries.forEach { section ->
            assertEquals(section, SettingsRoute(section.name).toSection())
        }
    }

    @Test
    fun unknownSectionFallsBackToBusinessRules() {
        assertEquals(
            SettingsSection.BUSINESS_RULES,
            SettingsRoute("A_SECTION_THAT_WAS_RENAMED").toSection(),
        )
    }

    @Test
    fun defaultRouteIsBusinessRules() {
        // Assert the raw field as well as the resolved section: an unknown string also resolves to
        // BUSINESS_RULES, so checking only toSection() would pass on a typo'd default.
        assertEquals("BUSINESS_RULES", SettingsRoute().section)
        assertEquals(SettingsSection.BUSINESS_RULES, SettingsRoute().toSection())
    }
}
