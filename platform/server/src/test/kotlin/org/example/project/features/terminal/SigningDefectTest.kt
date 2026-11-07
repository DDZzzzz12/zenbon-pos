package org.example.project.features.terminal

import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.features.terminal.domain.SigningDefect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SigningDefectTest {
    private fun of(
        active: Boolean = true,
        fiscal: Boolean = true,
        registered: Boolean = true,
        mode: TseMode = TseMode.NORMAL,
    ) = SigningDefect.of(isActive = active, fiscal = fiscal, tseClientRegistered = registered, mode = mode)

    @Test
    fun `a fit till has no defect`() = assertNull(of())

    @Test
    fun `inactive wins in every mode`() {
        TseMode.entries.forEach { assertEquals(SigningDefect.INACTIVE, of(active = false, mode = it), "mode=$it") }
    }

    @Test
    fun `DISABLED checks nothing but activity`() = assertNull(of(fiscal = false, registered = false, mode = TseMode.DISABLED))

    @Test
    fun `non-fiscal is refused outside DISABLED`() = assertEquals(SigningDefect.NOT_FISCAL, of(fiscal = false))

    @Test
    fun `unregistered is refused only in NORMAL`() {
        assertEquals(SigningDefect.NOT_REGISTERED, of(registered = false))
        TseMode.entries.filter { it != TseMode.NORMAL && it != TseMode.DISABLED }.forEach {
            assertNull(of(registered = false, mode = it), "mode=$it")
        }
    }
}
