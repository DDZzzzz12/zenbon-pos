package org.example.project.core.browser

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserActivityTest {
    private val here = Offset(10f, 20f)

    @Test
    fun `a synthetic move at an unchanged position is not activity`() {
        assertFalse(isUserActivity(PointerEventType.Move, here, here))
    }

    @Test
    fun `a move to a new position is activity`() {
        assertTrue(isUserActivity(PointerEventType.Move, Offset(11f, 20f), here))
    }

    @Test
    fun `the first move with no previous position is activity`() {
        assertTrue(isUserActivity(PointerEventType.Move, here, null))
    }

    @Test
    fun `press release and scroll are always activity`() {
        for (type in listOf(PointerEventType.Press, PointerEventType.Release, PointerEventType.Scroll)) {
            assertTrue(isUserActivity(type, here, here), "$type")
        }
    }

    @Test
    fun `enter and exit are never activity`() {
        assertFalse(isUserActivity(PointerEventType.Enter, Offset(1f, 1f), here))
        assertFalse(isUserActivity(PointerEventType.Exit, Offset(1f, 1f), here))
    }
}
