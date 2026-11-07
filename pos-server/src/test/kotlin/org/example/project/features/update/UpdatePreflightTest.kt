package org.example.project.features.update

import kotlinx.coroutines.test.runTest
import org.example.project.features.update.data.service.UpdatePreflight
import org.example.project.features.update.domain.models.PreflightVerdict
import org.example.project.infrastructure.di.DATABASE_BACKUP_HEADROOM_BYTES
import org.example.project.infrastructure.di.requiredInstallBytes
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpdatePreflightTest {
    /**
     * Every probe defaults to "quiet", so each test overrides exactly one. That is what proves a
     * condition is genuinely wired up rather than shadowed by an earlier guard.
     */
    private fun preflight(
        hasOpenBusiness: Boolean = false,
        openSessions: Int = 0,
        hasOpenTseWork: Boolean = false,
        runningJobs: Int = 0,
        freeDiskBytes: Long = 10_000L,
        requiredBytes: Long = 1_000L,
    ) = UpdatePreflight(
        hasOpenBusiness = { hasOpenBusiness },
        countOpenSessions = { openSessions },
        hasOpenTseWork = { hasOpenTseWork },
        countRunningJobs = { runningJobs },
        freeDiskBytes = { freeDiskBytes },
        requiredBytes = { requiredBytes },
    )

    private fun blocked(reason: PreflightVerdict.Reason) = PreflightVerdict.Blocked(reason)

    @Test fun `clear when everything is quiet`() =
        runTest {
            assertEquals(PreflightVerdict.Clear, preflight().evaluate())
        }

    @Test fun `blocks on open business`() =
        runTest {
            assertEquals(
                blocked(PreflightVerdict.Reason.OPEN_BUSINESS),
                preflight(hasOpenBusiness = true).evaluate(),
            )
        }

    @Test fun `blocks on an open table session`() =
        runTest {
            assertEquals(
                blocked(PreflightVerdict.Reason.OPEN_TABLE_SESSION),
                preflight(openSessions = 1).evaluate(),
            )
        }

    @Test fun `blocks on open tse work`() =
        runTest {
            assertEquals(
                blocked(PreflightVerdict.Reason.OPEN_TSE_WORK),
                preflight(hasOpenTseWork = true).evaluate(),
            )
        }

    @Test fun `blocks on a running job`() =
        runTest {
            assertEquals(
                blocked(PreflightVerdict.Reason.JOB_RUNNING),
                preflight(runningJobs = 1).evaluate(),
            )
        }

    @Test fun `blocks on insufficient disk`() =
        runTest {
            assertEquals(
                blocked(PreflightVerdict.Reason.INSUFFICIENT_DISK),
                preflight(freeDiskBytes = 10L, requiredBytes = 5_000L).evaluate(),
            )
        }

    @Test fun `open business is reported first when several blockers apply`() =
        runTest {
            assertEquals(
                blocked(PreflightVerdict.Reason.OPEN_BUSINESS),
                preflight(hasOpenBusiness = true, openSessions = 3, runningJobs = 2).evaluate(),
            )
        }

    /** Request activity is not a guard any more: a busy server with nothing open is clear. */
    @Test fun `nothing open is clear however busy the server was`() =
        runTest {
            assertEquals(PreflightVerdict.Clear, preflight().evaluate())
        }

    @Test fun `every guard blocks`() =
        runTest {
            assertEquals(blocked(PreflightVerdict.Reason.OPEN_BUSINESS), preflight(hasOpenBusiness = true).evaluate())
            assertEquals(blocked(PreflightVerdict.Reason.OPEN_TABLE_SESSION), preflight(openSessions = 1).evaluate())
            assertEquals(blocked(PreflightVerdict.Reason.OPEN_TSE_WORK), preflight(hasOpenTseWork = true).evaluate())
            assertEquals(blocked(PreflightVerdict.Reason.JOB_RUNNING), preflight(runningJobs = 1).evaluate())
            assertEquals(
                blocked(PreflightVerdict.Reason.INSUFFICIENT_DISK),
                preflight(freeDiskBytes = 10L, requiredBytes = 1_000L).evaluate(),
            )
        }

    /** Disk is inclusive too: free space exactly equal to the requirement is enough. */
    @Test fun `free disk exactly equal to the requirement passes`() =
        runTest {
            assertEquals(
                PreflightVerdict.Clear,
                preflight(freeDiskBytes = 4_096L, requiredBytes = 4_096L).evaluate(),
            )
        }

    @Test fun `one byte short of the requirement blocks`() =
        runTest {
            assertEquals(
                blocked(PreflightVerdict.Reason.INSUFFICIENT_DISK),
                preflight(freeDiskBytes = 4_095L, requiredBytes = 4_096L).evaluate(),
            )
        }

    @Test fun `the disk requirement covers the staged jar and the backup copy the applier writes`() {
        val dir = Files.createTempDirectory("required").toFile()
        val staged = File(dir, "pos-server-1.2.0.jar").also { it.writeBytes(ByteArray(1_000)) }

        val required = requiredInstallBytes(staged.absolutePath)

        // The applier copies the running jar aside before writing the new one, so one staged
        // copy is never enough: it needs the .bak as well.
        assertTrue(
            required >= 2_000 + DATABASE_BACKUP_HEADROOM_BYTES,
            "expected room for the staged jar and its .bak, got $required",
        )
        dir.deleteRecursively()
    }
}
