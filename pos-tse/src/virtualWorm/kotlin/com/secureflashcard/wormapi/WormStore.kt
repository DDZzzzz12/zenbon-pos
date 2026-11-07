package com.secureflashcard.wormapi

import java.io.Closeable
import org.example.project.tse.virtualworm.VirtualWormDevice
import org.example.project.tse.virtualworm.VirtualWormRegistry

/**
 * Virtual test double replacing the SWIG/JNI-backed `WormStore` from `lib/WormAPI.jar`.
 *
 * The method surface mirrors the real class 1:1 (names, parameters, return and exception
 * types — verified against `javap` output 2026-07-14) so that pos-tse main classes,
 * which are compiled against the real jar, link against this class at test runtime.
 * Behavior is provided by an in-memory [VirtualWormDevice] shared per mount point.
 *
 * Device presence is controlled by the `tse.virtual.device` system property
 * (see [VirtualWormRegistry]): by default the constructor throws
 * WORM_ERROR_NO_WORM_CARD, emulating a host without an attached TSE — the same
 * behavior tests observe on a Linux/Windows machine without hardware.
 */
open class WormStore(mountPoint: String?) : Closeable {

    private val device: VirtualWormDevice = VirtualWormRegistry.open(mountPoint)

    companion object {
        @JvmStatic
        // Tracks the SDK version this double models (bundled jar is v6.0.0). Surfaced by
        // GET /status as sdkVersion, which is how a virtual run is identifiable at runtime.
        fun version(): String = "6.0.0-virtual"

        @JvmStatic
        fun isOnlineSdk(): Boolean = false

        @JvmStatic
        fun signatureAlgorithm(): String = "ecdsa-plain-SHA384"

        @JvmStatic
        fun logTimeFormat(): String = "unixTime"
    }

    fun keepAliveConfigure(intervalSeconds: Int) = device.keepAliveConfigure(intervalSeconds)

    fun keepAliveDisable() = device.keepAliveDisable()

    fun info(): WormInformation = WormInformation(device)

    fun flashHealthSummary(): WormFlashHealthSummary = device.flashHealthSummary()

    fun tse_factoryReset() = device.factoryReset()

    fun tse_setup(
        credentialSeed: ByteArray?,
        adminPuk: ByteArray?,
        adminPin: ByteArray?,
        timeAdminPin: ByteArray?,
        clientId: String?,
    ) = device.setup(credentialSeed, adminPuk, adminPin, timeAdminPin, clientId)

    fun tse_setup_ext(
        credentialSeed: ByteArray?,
        adminPuk: ByteArray?,
        adminPin: ByteArray?,
        timeAdminPin: ByteArray?,
        clientId: String?,
        enableAutopilot: Boolean,
    ) = device.setupExt(credentialSeed, adminPuk, adminPin, timeAdminPin, clientId, enableAutopilot)

    fun tse_setup_ext(
        credentialSeed: ByteArray?,
        adminPuk: ByteArray?,
        adminPin: ByteArray?,
        timeAdminPin: ByteArray?,
        clientId: String?,
    ) = device.setupExt(credentialSeed, adminPuk, adminPin, timeAdminPin, clientId, false)

    fun tse_needs_setup(): Boolean = device.needsSetup()

    // NOTE: tse_startup, tse_autopilot_enable and tse_autopilot_disable are deliberately
    // NOT implemented. Production code does not call them (design spec 5.0) -- both
    // require the ERS to hold the Admin PIN, which the Swissbit guidance manual forbids.
    // Offering them here would invite accidental use.

    fun tse_ctss_enable() = device.ctssEnable()

    fun tse_ctss_disable() = device.ctssDisable()

    fun tse_initialize() = device.initialize()

    fun tse_decommission() = device.decommission()

    fun tse_updateTime(unixTimeSeconds: Long) = device.updateTime(unixTimeSeconds)

    fun tse_firmwareUpdate_check(): WormFirmwareUpdateCheckResponse = device.firmwareUpdateCheck()

    fun tse_firmwareUpdate(firmware: ByteArray?) = device.firmwareUpdate(firmware)

    fun tse_firmwareUpdate_isBundledAvailable(): WormTseFirmwareUpdate = device.firmwareUpdateBundledAvailable()

    fun tse_firmwareUpdate_applyBundled() = device.firmwareUpdateApplyBundled()

    fun tse_enableExportIfCspTestFails() = device.enableExportIfCspTestFails()

    fun tse_disableExportIfCspTestFails() = device.disableExportIfCspTestFails()

    /**
     * String-taking overload. Rejects null the way the real thing does.
     *
     * The SWIG wrapper null-checks the jstring before the SDK ever sees it
     * (WormAPI_swig_java.cpp: `if(!jarg2) { SWIG_JavaThrowException(..., "null string"); }`),
     * so a null here is a NullPointerException, NOT a WormException — which means the
     * `catch (WormException)` blocks wrapped around these calls do not catch it. The double
     * used to accept null and quietly succeed on v2, so a production-shaped null was
     * invisible to every test. Use [tse_runSelfTest] with no argument to omit the client.
     */
    fun tse_runSelfTest(clientId: String?) =
        device.runSelfTest(requireNonNullString(clientId))

    fun tse_runSelfTest() = device.runSelfTest(null)

    fun tse_registerClient(clientId: String?) =
        device.registerClient(requireNonNullString(clientId))

    fun tse_deregisterClient(clientId: String?) =
        device.deregisterClient(requireNonNullString(clientId))

    fun tse_listRegisteredClients(): Array<String> = device.listRegisteredClients()

    /** Mirrors the SWIG wrapper's jstring null check, which precedes every SDK call. */
    private fun requireNonNullString(value: String?): String =
        value ?: throw NullPointerException("null string")

    fun user_login(userId: WormUserId?, pin: ByteArray?) = device.login(userId, pin)

    fun user_logout(userId: WormUserId?) = device.logout(userId)

    fun user_unblock(userId: WormUserId?, puk: ByteArray?, newPin: ByteArray?) = device.unblock(userId, puk, newPin)

    fun user_change_puk(oldPuk: ByteArray?, newPuk: ByteArray?) = device.changePuk(oldPuk, newPuk)

    fun user_change_puk_ext(userId: WormUserId?, puk: ByteArray?, newPuk: ByteArray?) =
        device.changePukExt(userId, puk, newPuk)

    fun user_change_pin(userId: WormUserId?, oldPin: ByteArray?, newPin: ByteArray?) =
        device.changePin(userId, oldPin, newPin)

    fun user_deriveInitialCredentials(credentialSeed: ByteArray?): WormInitialCredentials =
        device.deriveInitialCredentials(credentialSeed)

    fun transaction_start(clientId: String?, processData: ByteArray?, processType: String?): WormTransactionResponse =
        device.transactionStart(clientId, processData, processType)

    fun transaction_update(
        clientId: String?,
        transactionNumber: Long,
        processData: ByteArray?,
        processType: String?,
    ): WormTransactionResponse = device.transactionUpdate(clientId, transactionNumber, processData, processType)

    fun transaction_finish(
        clientId: String?,
        transactionNumber: Long,
        processData: ByteArray?,
        processType: String?,
    ): WormTransactionResponse = device.transactionFinish(clientId, transactionNumber, processData, processType)

    fun transaction_lastResponse(clientId: String?): WormTransactionResponse = device.lastResponse(clientId)

    fun transaction_lastResponse(): WormTransactionResponse = device.lastResponse(null)

    fun transaction_listStartedTransactions(clientId: String?): LongArray = device.listStartedTransactions(clientId)

    fun transaction_listStartedTransactions(): LongArray = device.listStartedTransactions(null)

    fun firstEntry(): WormEntry = WormEntry(device, 0)

    fun lastEntry(): WormEntry = WormEntry(device, device.entryCount() - 1)

    fun entryForId(id: Long): WormEntry = WormEntry(device, device.indexOfEntryId(id))

    fun getLogMessageCertificate(): ByteArray = device.logMessageCertificate()

    fun export_tar(callback: WormExportTarCallback?) =
        device.exportTar { chunk -> requireCallback(callback).onNewData(chunk) }

    fun export_tar_incremental(
        state: ByteArray?,
        callback: WormExportTarIncrementalCallback?,
    ): WormExportTarIncrementalResponse =
        device.exportTarIncremental(state, Long.MAX_VALUE) { chunk, processed, total ->
            requireIncrementalCallback(callback).onNewData(chunk, processed, total)
        }

    fun export_tar_incremental_ex(
        state: ByteArray?,
        maxExportSize: Long,
        callback: WormExportTarIncrementalCallback?,
    ): WormExportTarIncrementalResponse =
        device.exportTarIncremental(state, maxExportSize) { chunk, processed, total ->
            requireIncrementalCallback(callback).onNewData(chunk, processed, total)
        }

    fun export_tar_incremental_sizeInSectors(state: ByteArray?): Long = device.exportIncrementalSizeInSectors(state)

    fun export_tar_incremental_size(state: ByteArray?): Long = device.exportIncrementalSize(state)

    fun export_tar_filtered_time(
        startDate: Long,
        endDate: Long,
        clientId: String?,
        callback: WormExportTarCallback?,
    ) = device.exportTarFilteredTime(startDate, endDate, requireNonNullString(clientId)) { chunk ->
        requireCallback(callback).onNewData(chunk)
    }

    fun export_tar_filtered_transaction(
        transactionNumberStart: Long,
        transactionNumberEnd: Long,
        clientId: String?,
        callback: WormExportTarCallback?,
    ) = device.exportTarFilteredTransaction(
        transactionNumberStart,
        transactionNumberEnd,
        requireNonNullString(clientId),
    ) { chunk ->
        requireCallback(callback).onNewData(chunk)
    }

    fun export_lcm_file(path: String?): String = device.exportLcmFile(path)

    fun export_deleteStoredData() = device.exportDeleteStoredData()

    fun delete() {}

    override fun close() {}

    private fun requireCallback(callback: WormExportTarCallback?): WormExportTarCallback =
        callback ?: throw WormException(WormError.WORM_ERROR_INVALID_PARAMETER.swigValue())

    private fun requireIncrementalCallback(
        callback: WormExportTarIncrementalCallback?,
    ): WormExportTarIncrementalCallback =
        callback ?: throw WormException(WormError.WORM_ERROR_INVALID_PARAMETER.swigValue())
}
