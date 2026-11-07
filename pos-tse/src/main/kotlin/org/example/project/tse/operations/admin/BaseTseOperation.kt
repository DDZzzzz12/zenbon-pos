package org.example.project.tse.operations.admin

import com.secureflashcard.wormapi.WormException
import com.secureflashcard.wormapi.WormStore
import com.secureflashcard.wormapi.WormUserId
import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.error.PosErrorCode
import org.example.project.infrastructure.errors.PosException
import org.example.project.tse.models.TseStatus
import org.example.project.tse.operations.ITseOperation
import org.example.project.tse.operations.signing.WormErrorClass
import org.example.project.tse.utils.TseLocator
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Abstract base class for TSE administrative operations.
 *
 * Provides common functionality shared across all admin operations:
 * - Connection validation
 * - Lock handling via TseStatus.executeOnTseThread
 * - Admin login helper methods
 * - Consistent error handling
 *
 * Subclasses only need to implement [performOperation] with their specific logic.
 *
 * Thread Safety:
 * - All WormAPI operations are automatically wrapped in executeOnTseThread
 * - Only one admin command executes at a time
 * - Concurrent requests are queued in FIFO order
 *
 * @param T The return type of the operation result
 * @param adminPin The admin PIN for TSE authentication (may be empty for some operations)
 * @param requiresConnection Whether this operation requires TSE hardware connection (default: true)
 * @param requiresLock Whether this operation requires exclusive lock (default: true)
 *
 * @see org.example.project.tse.operations.ITseOperation
 * @see TseStatus.executeOnTseThread
 * @since 2.0.0
 */
abstract class BaseTseOperation<T>(
    protected val adminPin: String,
    override val requiresConnection: Boolean = true,
    override val requiresLock: Boolean = true
) : ITseOperation<T> {

    protected val logger: Logger = LoggerFactory.getLogger(this::class.java)

    /**
     * Execute the admin operation with proper connection/lock handling.
     *
     * This method:
     * 1. Validates connection if required
     * 2. Acquires lock if required
     * 3. Delegates to [performOperation] for actual work
     *
     * @param tseStatus Current TSE status and configuration
     * @return Operation-specific data on success.
     * @throws PosException if the operation fails per the B1 cross-walk.
     */
    @Throws(PosException::class)
    override fun execute(tseStatus: TseStatus): T {
        logger.info("Executing ${this::class.simpleName}")

        if (!tseStatus.isConnected() && tseStatus.getMountPoint() == null) {
            val mountPoint = TseLocator.findAndValidateTse()
                ?: throw PosException(
                    code = PosErrorCode.TSE_INVALID_MOUNT_POINT,
                    httpStatus = HttpStatusCode.ServiceUnavailable,
                    message = "Invalid TSE mount point: No mount point specified/found",
                )
            tseStatus.setMountPoint(mountPoint)
        }


        // Validate connection for operations that require it
        if (requiresConnection && tseStatus.getMode() == TseMode.NORMAL && !tseStatus.isConnected()) {
            logger.error("TSE not connected for operation: ${this::class.simpleName}")
            throw PosException(
                code = PosErrorCode.TSE_HARDWARE_NOT_FOUND,
                httpStatus = HttpStatusCode.ServiceUnavailable,
                message = "TSE hardware not found at mount point: TSE not found under Normal mode",
            )
        }


        // Execute with or without lock based on operation requirements
        return try {
            if (requiresLock) {
                tseStatus.executeOnTseThread {
                    performOperation(tseStatus)
                }
            } else {
                performOperation(tseStatus)
            }
        } catch (e: WormException) {
            // E2E R2-6, admin path: sort by the SDK's own split, exactly as the signing path does.
            // Only a host-side error means the device may be gone; an answer from the card proves
            // it is connected, and clearing the mount point for one made the NEXT admin call fail
            // too — worst of all during commissioning, when the device answers "not ready yet" by
            // design. See WormErrorClass for the boundary and its residuals.
            val errorClass = WormErrorClass.of(e)
            if (errorClass.resetsConnection) {
                logger.warn("TSE connection error during ${this::class.simpleName}: ${e.message}")
                tseStatus.handleHardwareError(e)
            } else {
                logger.warn("TSE answered ${e.message} during ${this::class.simpleName}; connection kept")
            }
            throw PosException(
                code = errorClass.code,
                httpStatus = errorClass.httpStatus,
                message = errorClass.messageFor(e),
                cause = e,
            )
        }
    }

    /**
     * Perform the actual operation logic.
     *
     * This method is called after connection validation and lock acquisition (if needed).
     * Subclasses should implement their specific operation logic here.
     *
     * Important:
     * - Do NOT call executeOnTseThread inside this method (it's already handled)
     * - Use [getWormStore] to access the TSE hardware
     * - Use [loginAsAdmin] or [loginAsTimeAdmin] for authentication
     * - On failure, throw [PosException] with the appropriate [PosErrorCode] per the
     *   cross-walk in `docs/superpowers/plans/2026-05-04-poserror-tse-migration.md`.
     *
     * @param tseStatus Current TSE status and configuration
     * @return Operation-specific data on success.
     * @throws PosException if the operation fails.
     */
    @Throws(PosException::class)
    protected abstract fun performOperation(tseStatus: TseStatus): T

    /**
     * Get the WormStore instance for TSE operations.
     *
     * This is a convenience method that delegates to tseStatus.getWormStore().
     * Should only be called within performOperation (after lock is acquired).
     *
     * @param tseStatus The TSE status containing the WormStore instance
     * @return The WormStore instance
     * @throws IllegalStateException if not connected
     */
    protected fun getWormStore(tseStatus: TseStatus): WormStore {
        return tseStatus.getWormStore()
    }

    /**
     * Login as admin user.
     *
     * Authenticates with the TSE using the admin PIN provided in constructor.
     * Must be called before operations that require admin privileges.
     *
     * @param store The WormStore instance
     * @throws com.secureflashcard.wormapi.WormException if login fails
     */
    protected fun loginAsAdmin(store: WormStore) {
        store.user_login(
            WormUserId.WORM_USER_ADMIN,
            adminPin.toByteArray(Charsets.UTF_8)
        )
        logger.debug("Admin login successful")
    }

    /**
     * Login as time admin user.
     *
     * Authenticates with the TSE using the time admin PIN.
     * Required for time-related operations.
     *
     * @param store The WormStore instance
     * @param timeAdminPin The time admin PIN
     * @throws com.secureflashcard.wormapi.WormException if login fails
     */
    protected fun loginAsTimeAdmin(store: WormStore, timeAdminPin: String) {
        store.user_login(
            WormUserId.WORM_USER_TIME_ADMIN,
            timeAdminPin.toByteArray(Charsets.UTF_8)
        )
        logger.debug("Time admin login successful")
    }
}
