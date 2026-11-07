package org.example.project.admin.core.presentation.navigation

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.core.presentation.strings.translations.GermanAdminStrings
import org.example.project.core.presentation.strings.StringKey
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * R6-4: the sidebar footer labelled a user whose permission GROUP is Auditor with their base ROLE,
 * "Kassierer". The group is what the server enforces, so it is what the footer names.
 */
class UserMenuIdentityLabelTest {
    private val german: (StringKey) -> String = { key -> GermanAdminStrings.strings[key] ?: key.id }

    @Test
    fun auditorWithCashierRole_isLabelledByTheGroup() {
        val label = userMenuIdentityLabel("AUDITOR", "Auditor", roleLabel = "Kassierer", translate = german)
        assertEquals(GermanAdminStrings.strings.getValue(AdminStringKeys.PermissionGroupAuditor), label)
    }

    @Test
    fun seededGroup_isTranslatedByCodeNotByItsStoredEnglishName() {
        val label = userMenuIdentityLabel("ACCOUNTANT", "Accountant", roleLabel = "Manager", translate = german)
        assertEquals(GermanAdminStrings.strings.getValue(AdminStringKeys.PermissionGroupAccountant), label)
    }

    @Test
    fun customGroup_showsItsStoredName() {
        assertEquals("Buchhaltung Teilzeit", userMenuIdentityLabel("BH_TZ", "Buchhaltung Teilzeit", "Kassierer", german))
    }

    @Test
    fun customGroupWithoutAName_showsItsCode() {
        assertEquals("BH_TZ", userMenuIdentityLabel("BH_TZ", null, "Kassierer", german))
    }

    // A session stored before the server reported the group, or an employee with none.
    @Test
    fun noGroup_fallsBackToTheRole() {
        assertEquals("Kassierer", userMenuIdentityLabel(null, null, "Kassierer", german))
    }
}
