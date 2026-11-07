package com.secureflashcard.wormapi

import java.io.Closeable
import org.example.project.tse.virtualworm.VirtualWormDevice

/**
 * Virtual test double replacing the SWIG-generated info class from `lib/WormAPI.jar`.
 *
 * Like the real class, values are a snapshot: captured when `WormStore.info()` creates
 * this object and refreshed only by an explicit [read].
 */
class WormInformation internal constructor(
    private val device: VirtualWormDevice,
) : Closeable {

    private var snapshot: VirtualWormDevice.Snapshot = device.snapshot()

    fun read() {
        snapshot = device.snapshot()
    }

    fun customizationIdentifier(): ByteArray = snapshot.customizationIdentifier.copyOf()

    fun isDevelopmentFirmware(): Boolean = snapshot.isDevelopmentFirmware

    fun capacity(): Long = snapshot.capacity

    fun size(): Long = snapshot.size

    fun hasValidTime(): Boolean = snapshot.hasValidTime

    fun hasPassedSelfTest(): Boolean = snapshot.hasPassedSelfTest

    fun isCtssInterfaceActive(): Boolean = snapshot.isCtssInterfaceActive

    fun isExportEnabledIfCspTestFails(): Boolean = snapshot.isExportEnabledIfCspTestFails

    fun initializationState(): WormInitializationState = snapshot.initializationState

    fun isDataImportInProgress(): Boolean = snapshot.isDataImportInProgress

    fun hasChangedPuk(): Boolean = snapshot.hasChangedPuk

    fun hasChangedAdminPin(): Boolean = snapshot.hasChangedAdminPin

    fun hasChangedTimeAdminPin(): Boolean = snapshot.hasChangedTimeAdminPin

    fun timeUntilNextSelfTest(): Long = snapshot.timeUntilNextSelfTest

    fun startedTransactions(): Long = snapshot.startedTransactions

    fun maxStartedTransactions(): Long = snapshot.maxStartedTransactions

    fun createdSignatures(): Long = snapshot.createdSignatures

    fun maxSignatures(): Long = snapshot.maxSignatures

    fun remainingSignatures(): Long = snapshot.remainingSignatures

    fun maxTimeSynchronizationDelay(): Long = snapshot.maxTimeSynchronizationDelay

    fun maxUpdateDelay(): Long = snapshot.maxUpdateDelay

    fun tsePublicKey(): ByteArray = snapshot.tsePublicKey.copyOf()

    fun timeUntilNextTimeSynchronization(): Long = snapshot.timeUntilNextTimeSynchronization

    fun tseSerialNumber(): ByteArray = snapshot.tseSerialNumber.copyOf()

    fun tseDescription(): String = snapshot.tseDescription

    fun registeredClients(): Long = snapshot.registeredClients

    fun maxRegisteredClients(): Long = snapshot.maxRegisteredClients

    fun certificateExpirationDate(): Long = snapshot.certificateExpirationDate

    fun tarExportSizeInSectors(): Long = snapshot.tarExportSizeInSectors

    fun tarExportSize(): Long = snapshot.tarExportSize

    fun hardwareVersion(): Long = snapshot.hardwareVersion

    fun softwareVersion(): Long = snapshot.softwareVersion

    fun isTSEv2(): Boolean = snapshot.isTSEv2

    fun loggedInUser(): WormUserId = snapshot.loggedInUser

    fun loggedInUserAutoLogOutTimeout(): Int = snapshot.loggedInUserAutoLogOutTimeout

    fun hasChangedAdminPuk(): Boolean = snapshot.hasChangedAdminPuk

    fun hasChangedTimeAdminPuk(): Boolean = snapshot.hasChangedTimeAdminPuk

    fun hasChangedLoggerPuk(): Boolean = snapshot.hasChangedLoggerPuk

    fun hasChangedLoggerPin(): Boolean = snapshot.hasChangedLoggerPin

    fun formFactor(): String = snapshot.formFactor

    fun delete() {}

    override fun close() {}
}
