package org.example.project.features.update

import kotlinx.coroutines.test.runTest
import org.example.project.features.update.data.service.UpdateBootReconciler
import org.example.project.features.update.domain.models.UpdateCheckOutcome
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateBootReconcilerTest {
    private val installDir: File = Files.createTempDirectory("reconciler").toFile()
    private val marker = File(installDir, "update-applying.json")
    private val audits = mutableListOf<Pair<String, String>>()

    @AfterTest fun tearDown() {
        installDir.deleteRecursively()
    }

    private fun writeMarker(target: String) {
        marker.writeText(
            """{"targetVersion":"$target","databaseBackupPath":"/var/backups/pre-1.3.0.dump","startedAt":"2026-09-01T02:00:00Z"}""",
        )
    }

    private val statuses = mutableListOf<UpdateCheckOutcome>()

    private fun reconciler(runningVersion: String) =
        UpdateBootReconciler(
            installDir = installDir,
            runningVersion = runningVersion,
            recordAudit = { operation, detail -> audits += operation to detail },
            recordStatus = { statuses += it },
        )

    @Test fun `a rollback replaces the stale applying status with UPDATE_ROLLED_BACK`() =
        runTest {
            writeMarker("1.3.0")
            reconciler("1.2.0").reconcile()
            assertEquals("UPDATE_ROLLED_BACK", statuses.single().outcome)
            assertEquals("1.3.0", statuses.single().availableVersion)
        }

    @Test fun `an applied update records UP_TO_DATE`() =
        runTest {
            writeMarker("1.3.0")
            reconciler("1.3.0").reconcile()
            assertEquals("UP_TO_DATE", statuses.single().outcome)
        }

    @Test fun `does nothing when there is no marker`() =
        runTest {
            reconciler("1.2.0").reconcile()
            assertTrue(audits.isEmpty())
        }

    @Test fun `records the update as applied when the running version is the target`() =
        runTest {
            writeMarker("1.3.0")
            reconciler("1.3.0").reconcile()
            assertEquals(1, audits.size)
            assertEquals(UpdateBootReconciler.OPERATION_APPLIED, audits.single().first)
            assertTrue(audits.single().second.contains("1.3.0"))
            assertFalse(marker.exists(), "the marker must be cleared or a later apply cannot tell in-progress from long-finished")
        }

    @Test fun `records a rollback when the running version differs from the target`() =
        runTest {
            writeMarker("1.3.0")
            reconciler("1.2.0").reconcile()
            assertEquals(1, audits.size)
            assertEquals(UpdateBootReconciler.OPERATION_ROLLED_BACK, audits.single().first)
            assertTrue(
                audits.single().second.contains("/var/backups/pre-1.3.0.dump"),
                "the rollback audit must name the pre-apply database backup -- a jar rollback cannot undo a migration",
            )
            assertFalse(marker.exists())
        }

    @Test fun `deletes an unparseable marker without throwing`() =
        runTest {
            marker.writeText("{ not json")
            reconciler("1.2.0").reconcile()
            assertFalse(marker.exists())
            assertTrue(audits.isEmpty())
        }

    @Test fun `never propagates a failure -- a broken reconciler must not stop the boot`() =
        runTest {
            writeMarker("1.3.0")
            UpdateBootReconciler(
                installDir = installDir,
                runningVersion = "1.3.0",
                recordAudit = { _, _ -> error("audit table unavailable") },
            ).reconcile()
        }
}
