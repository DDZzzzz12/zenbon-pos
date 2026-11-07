package org.example.project.tse.operations.admin.users

import com.secureflashcard.wormapi.WormUserId
import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.error.PosErrorCode
import org.example.project.infrastructure.errors.PosException
import org.example.project.tse.operations.admin.BaseTseOperation
import org.example.project.tse.models.TseStatus

/**
 * Change a user's PIN.
 *
 * Changes the PIN for a TSE user. Requires the current PIN for authentication.
 *
 * Supported Users:
 * - ADMIN: Administrator user
 * - TIME_ADMIN: Time administrator user
 *
 * PIN Requirements:
 * - Must be 5-6 digits
 * - Must be different from current PIN
 * - Incorrect current PIN will increment fail counter
 *
 * WormAPI Method: user_change_pin() - line 1010
 *
 * Endpoint: POST /admin/users/pin
 *
 * @param adminPin Admin PIN for TSE authentication (not used directly)
 * @param userId User whose PIN to change (ADMIN or TIME_ADMIN)
 * @param currentPin Current PIN for authentication
 * @param newPin New PIN to set
 * @since 2.0.0
 */
class ChangePinOperation(
    adminPin: String,
    private val userId: String = "ADMIN",
    private val currentPin: String,
    private val newPin: String
) : BaseTseOperation<TseDto.AdminResultDto>(adminPin) {

    /**
     * Change user PIN.
     *
     * @param tseStatus Current TSE status and configuration
     * @return Result.Success with PIN change confirmation or Result.Error
     */
    override fun performOperation(tseStatus: TseStatus): TseDto.AdminResultDto {
        logger.info("Changing PIN for user: $userId")

        val store = getWormStore(tseStatus)

        val wormUserId = when (userId.uppercase()) {
            "ADMIN" -> WormUserId.WORM_USER_ADMIN
            "TIME_ADMIN" -> WormUserId.WORM_USER_TIME_ADMIN
            else -> WormUserId.WORM_USER_ADMIN
        }

        // worm_user_change_pin requires "the user must have been logged in before with
        // worm_user_login" -- the user WHOSE PIN is changing, not Admin. This operation
        // performed no login at all, which worked only while the TSE 1.x startup sequence
        // left Admin logged in and v1 had no idle logout. Neither holds on TSE 2.0, and
        // startup no longer logs Admin in on v1 either.
        store.user_login(wormUserId, currentPin.toByteArray(Charsets.UTF_8))

        store.user_change_pin(
            wormUserId,
            currentPin.toByteArray(Charsets.UTF_8),
            newPin.toByteArray(Charsets.UTF_8)
        )

        logger.info("PIN changed successfully for user: $userId")

        return TseDto.AdminResultDto(
            success = true,
            message = "PIN changed successfully for user $userId"
        )
    }

    companion object {
        /**
         * Create ChangePinOperation from request parameters map.
         *
         * @param adminPin Admin PIN (used as default for currentPin)
         * @param parameters Map containing userId, currentPin, newPin
         * @return Result with operation or error
         */
        @Throws(PosException::class)
        fun fromParameters(
            adminPin: String,
            parameters: Map<String, String>
        ): ChangePinOperation {
            val currentPin = parameters["currentPin"]
                ?: throw PosException(
                    code = PosErrorCode.TSE_INVALID_CMD,
                    httpStatus = HttpStatusCode.BadRequest,
                    message = "Invalid TSE admin command: changepin requires 'currentPin' parameter",
                )

            val newPin = parameters["newPin"]
                ?: throw PosException(
                    code = PosErrorCode.TSE_INVALID_CMD,
                    httpStatus = HttpStatusCode.BadRequest,
                    message = "Invalid TSE admin command: changepin requires 'newPin' parameter",
                )

            return ChangePinOperation(
                adminPin = adminPin,
                userId = parameters["userId"] ?: "ADMIN",
                currentPin = currentPin,
                newPin = newPin
            )
        }
    }
}
