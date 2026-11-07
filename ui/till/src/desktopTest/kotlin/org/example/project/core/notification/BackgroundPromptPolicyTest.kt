package org.example.project.core.notification

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackgroundPromptPolicyTest {
    private val now = 1_000_000_000L

    private fun prompt(
        ignoring: Boolean = false,
        sent: Boolean = false,
        postponedUntil: Long = 0L,
    ) = BackgroundPromptPolicy.shouldPrompt(ignoring, sent, postponedUntil, now)

    @Test
    fun `a first login asks`() = assertTrue(prompt())

    @Test
    fun `later holds the question back for a week, not just one login`() {
        val until = now + BackgroundPromptPolicy.POSTPONE_MILLIS
        assertFalse(prompt(postponedUntil = until))
        assertFalse(BackgroundPromptPolicy.shouldPrompt(false, false, until, now + BackgroundPromptPolicy.POSTPONE_MILLIS - 1))
        assertTrue(BackgroundPromptPolicy.shouldPrompt(false, false, until, now + BackgroundPromptPolicy.POSTPONE_MILLIS))
    }

    @Test
    fun `an exempt app or one already sent to the settings is never asked`() {
        assertFalse(prompt(ignoring = true))
        assertFalse(prompt(sent = true))
    }
}
