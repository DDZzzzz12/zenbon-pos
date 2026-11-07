package org.example.project.tse.operations

import com.secureflashcard.wormapi.WormUserId
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.tse.models.TseStatus
import org.example.project.tse.operations.admin.export.TimeFilteredExportOperation
import org.example.project.tse.operations.admin.export.TransactionFilteredExportOperation
import org.example.project.tse.virtual.VirtualTseTestSupport
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A filtered export must survive an unconfigured client.
 *
 * Both filtered exports passed `tseStatus.getClientId()` straight into the SDK, and that
 * wrapper rejects a null jstring before the SDK sees it — a NullPointerException, which the
 * `catch (WormException)` around export code does not catch. The SDK documents the empty
 * string as "all clients", so there is a correct value for "no filter"; null never was one.
 *
 * These run with no client configured deliberately. That was the production shape until the
 * client ID was actually plumbed through, and an export is exactly the operation an auditor
 * asks for at the worst moment.
 */
class FilteredExportWithoutClientTest {

    private val tseStatus = TseStatus()

    @AfterEach
    fun tearDown() {
        tseStatus.close()
        VirtualTseTestSupport.detachDevice()
    }

    private fun commissionedWithoutClientId() {
        // v1: TSE 2.0 removed filtered exports altogether, so this pair of operations only
        // ever runs against the older generation.
        VirtualTseTestSupport.attachDevice("v1")
        tseStatus.setMountPoint(VirtualTseTestSupport.MOUNT_POINT)
        tseStatus.setMode(TseMode.NORMAL)
        tseStatus.setTimeAdminPin("98765")
        assertTrue(tseStatus.detectHardware())
        tseStatus.getWormStore().tse_setup_ext(
            "SwissbitSwissbit".toByteArray(), "888888".toByteArray(),
            "54321".toByteArray(), "98765".toByteArray(), "TER-001",
        )
        tseStatus.getWormStore().tse_updateTime(System.currentTimeMillis() / 1000)

        // Something to export. Signed under TER-001, which is exactly the point: with no
        // client configured the export must return it rather than filtering it out.
        tseStatus.getWormStore().transaction_start("TER-001", "data".toByteArray(), "Kassenbeleg-V1")

        runCatching { tseStatus.getWormStore().user_logout(WormUserId.WORM_USER_ADMIN) }
        // Note: no setClientId. getClientId() is null here.
    }

    @Test
    fun `a transaction-filtered export runs with no client configured`(@TempDir dir: File) {
        commissionedWithoutClientId()
        val out = File(dir, "tx-filtered.tar")

        TransactionFilteredExportOperation(
            adminPin = "",
            startTransaction = 1,
            endTransaction = 1,
            outputPath = out.absolutePath,
        ).execute(tseStatus)

        assertTrue(out.exists(), "the export must have produced a file")
    }

    @Test
    fun `a time-filtered export runs with no client configured`(@TempDir dir: File) {
        commissionedWithoutClientId()
        val out = File(dir, "time-filtered.tar")
        // Milliseconds: the operation converts to the device's seconds itself.
        val nowMs = System.currentTimeMillis()

        TimeFilteredExportOperation(
            adminPin = "",
            startDate = nowMs - 3_600_000,
            endDate = nowMs + 60_000,
            outputPath = out.absolutePath,
        ).execute(tseStatus)

        assertTrue(out.exists(), "the export must have produced a file")
    }
}
