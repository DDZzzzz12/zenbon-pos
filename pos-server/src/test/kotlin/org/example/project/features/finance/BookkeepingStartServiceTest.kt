package org.example.project.features.finance

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.example.project.features.finance.data.service.BookkeepingStartServiceImpl
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The default is written on first use, not declared in the enum: a date baked into
 * `ConfigKey.defaultValue` is already stale when the release ships.
 *
 * Two properties matter here and they used to be one. **Any** entry point creates the value —
 * including `cutoff()`, which the invoice list calls — because filtering correctness must not depend
 * on which screen the operator opened first. And the banner is driven by a **stored**
 * acknowledgement rather than by a one-shot flag on whichever response happened to do the write:
 * `get()` is therefore idempotent, and the banner survives a reload.
 */
@DisplayName("Buchungsbeginn setting")
class BookkeepingStartServiceTest {
    private val service = BookkeepingStartServiceImpl(ConfigurationRepository())

    private fun setup() {
        TestData.createShopInfo()
        TestData.createManager()
    }

    @Test
    fun `the first read writes the first day of the current month`() {
        testApp(setupData = { setup() }) { _ ->
            val first = runBlocking { service.get() }

            assertTrue(first.needsAcknowledgement, "nobody has confirmed the date yet")
            val date = LocalDate.parse(first.startDate)
            assertEquals(1, date.day, "the default is the first of the month")
        }
    }

    /**
     * The hole this replaced: `cutoff()` was a pure read returning null while the setting was
     * missing, and null means "filter nothing". A shop whose operator never opened Zuordnung had no
     * cut-off at all.
     */
    @Test
    fun `the cut-off creates the setting when there is none`() {
        testApp(setupData = { setup() }) { _ ->
            val cutoff = runBlocking { service.cutoff() }

            assertNotNull(cutoff, "reading the cut-off must establish it, not skip filtering")
            assertEquals(1, cutoff.date.day)
            assertEquals(cutoff.date.toString(), runBlocking { service.get() }.startDate)
        }
    }

    @Test
    fun `reading it twice is idempotent`() {
        testApp(setupData = { setup() }) { _ ->
            val first = runBlocking { service.get() }
            val second = runBlocking { service.get() }

            assertEquals(first.startDate, second.startDate)
            assertTrue(
                second.needsAcknowledgement,
                "a read must not consume the banner — a reload at the wrong moment would lose it",
            )
        }
    }

    @Test
    fun `acknowledging it is durable`() {
        testApp(setupData = { setup() }) { _ ->
            runBlocking { service.get() }

            val acknowledged = runBlocking { service.acknowledge() }
            assertFalse(acknowledged.needsAcknowledgement)

            assertFalse(
                runBlocking { service.get() }.needsAcknowledgement,
                "the acknowledgement is stored, so it survives a re-read",
            )
        }
    }

    @Test
    fun `choosing a date acknowledges it too`() {
        testApp(setupData = { setup() }) { _ ->
            runBlocking { service.get() }

            val updated = runBlocking { service.update("2025-01-01") }
            assertEquals("2025-01-01", updated.startDate)
            assertFalse(updated.needsAcknowledgement)

            val reread = runBlocking { service.get() }
            assertEquals("2025-01-01", reread.startDate)
            assertFalse(reread.needsAcknowledgement, "an operator who picked a date has seen it")
        }
    }

    @Test
    fun `a malformed date is rejected rather than stored`() {
        testApp(setupData = { setup() }) { _ ->
            runBlocking { service.get() }

            for (bad in listOf("", "  ", "01.09.2026", "2026-13-01", "nicht-ein-datum")) {
                val error =
                    kotlin.runCatching { runBlocking { service.update(bad) } }.exceptionOrNull()
                assertTrue(
                    error != null,
                    "'$bad' must be refused — a bad date here silently empties both work lists",
                )
            }
            assertFalse(runBlocking { service.get() }.startDate.isBlank())
        }
    }
}
