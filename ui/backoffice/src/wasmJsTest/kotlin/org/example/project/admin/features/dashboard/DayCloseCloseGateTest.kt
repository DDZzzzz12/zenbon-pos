package org.example.project.admin.features.dashboard

import org.example.project.admin.features.zBon.closing.ZBonClosingAccess
import org.example.project.core.domain.enums.auth.Capability
import kotlin.test.Test
import kotlin.test.assertEquals

class DayCloseCloseGateTest {
    @Test
    fun closeCapabilityAloneOffersOnlyTheBareClosing() {
        assertEquals(
            ZBonClosingAccess(mayClose = true, paysOutTips = false, opensZBon = false),
            ZBonClosingAccess.from { it == Capability.ZBON_CLOSE },
        )
    }

    @Test
    fun noCapabilityOffersNothing() {
        assertEquals(ZBonClosingAccess(false, false, false), ZBonClosingAccess.from { false })
    }

    @Test
    fun everyCapabilityOffersEverything() {
        assertEquals(ZBonClosingAccess(true, true, true), ZBonClosingAccess.from { true })
    }

    @Test
    fun payoutsAndOpeningFollowTheirOwnCapabilities() {
        val access = ZBonClosingAccess.from { it == Capability.CASHBOOK_WRITE || it == Capability.ZBON_MANAGE }
        assertEquals(ZBonClosingAccess(mayClose = false, paysOutTips = true, opensZBon = true), access)
    }
}
