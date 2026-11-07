package org.example.project.admin.features.issuereport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.example.project.admin.features.issuereport.presentation.ReportProblemLauncher
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportProblemLauncherTest {
    @Test
    fun `starts closed`() {
        assertFalse(ReportProblemLauncher().isOpen)
    }

    @Test
    fun `open and close flip the flag`() {
        val launcher = ReportProblemLauncher()
        launcher.open()
        assertTrue(launcher.isOpen)
        launcher.close()
        assertFalse(launcher.isOpen)
    }

    @Test
    fun `openAfter waits for the drawer to finish closing`() =
        runTest {
            val launcher = ReportProblemLauncher()
            val drawerClosed = CompletableDeferred<Unit>()
            val job = launch { launcher.openAfter { drawerClosed.await() } }
            testScheduler.runCurrent()
            assertFalse(launcher.isOpen, "the dialog must not open over a drawer that is still sliding shut")
            drawerClosed.complete(Unit)
            job.join()
            assertTrue(launcher.isOpen)
        }

    @Test
    fun `openAfter still opens when the drawer animation is interrupted`() =
        runTest {
            val launcher = ReportProblemLauncher()
            runCatching { launcher.openAfter { throw CancellationException("gesture took over") } }
            assertTrue(launcher.isOpen)
        }
}
