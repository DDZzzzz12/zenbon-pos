package org.example.project.ui.components

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PosDialogPhonePolicyTest {
    private val phone = 390.dp

    @Test
    fun autoMakesAWideFormFullScreenOnAPhone() {
        assertTrue(posDialogUsesFullScreen(phone, PosDialogPhoneMode.Auto, hasFooter = true, maxWidth = 520.dp))
    }

    @Test
    fun autoKeepsASmallConfirmCompact() {
        assertFalse(posDialogUsesFullScreen(phone, PosDialogPhoneMode.Auto, hasFooter = true, maxWidth = PosDialogDefaultMaxWidth))
    }

    @Test
    fun autoKeepsAFooterlessDialogCompact() {
        assertFalse(posDialogUsesFullScreen(phone, PosDialogPhoneMode.Auto, hasFooter = false, maxWidth = 640.dp))
    }

    @Test
    fun explicitModesOverrideAutoOnAPhone() {
        assertTrue(posDialogUsesFullScreen(phone, PosDialogPhoneMode.FullScreen, hasFooter = false, maxWidth = PosDialogDefaultMaxWidth))
        assertFalse(posDialogUsesFullScreen(phone, PosDialogPhoneMode.Compact, hasFooter = true, maxWidth = 640.dp))
    }

    @Test
    fun formIsFullScreenOnAPhoneWithDefaultMaxWidth() {
        assertTrue(
            posDialogUsesFullScreen(
                phone,
                PosDialogPhoneMode.Auto,
                hasFooter = true,
                maxWidth = PosDialogDefaultMaxWidth,
                kind = PosDialogKind.Form,
            ),
        )
    }

    @Test
    fun confirmStaysCompactOnAPhoneEvenWhenWide() {
        assertFalse(
            posDialogUsesFullScreen(phone, PosDialogPhoneMode.Auto, hasFooter = true, maxWidth = 640.dp, kind = PosDialogKind.Confirm),
        )
    }

    @Test
    fun nullKindFallsBackToTheLegacyRule() {
        assertTrue(posDialogUsesFullScreen(phone, PosDialogPhoneMode.Auto, hasFooter = true, maxWidth = 520.dp, kind = null))
        assertFalse(posDialogUsesFullScreen(phone, PosDialogPhoneMode.Auto, hasFooter = true, maxWidth = PosDialogDefaultMaxWidth, kind = null))
    }

    @Test
    fun formNeverChangesAtOrAboveThePhoneBreakpoint() {
        assertFalse(
            posDialogUsesFullScreen(600.dp, PosDialogPhoneMode.Auto, hasFooter = true, maxWidth = 640.dp, kind = PosDialogKind.Form),
        )
    }

    @Test
    fun tabletAndDesktopNeverChange() {
        assertFalse(posDialogUsesFullScreen(600.dp, PosDialogPhoneMode.Auto, hasFooter = true, maxWidth = 640.dp))
        assertFalse(posDialogUsesFullScreen(1400.dp, PosDialogPhoneMode.FullScreen, hasFooter = true, maxWidth = 640.dp))
    }
}
