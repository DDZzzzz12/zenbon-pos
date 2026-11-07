package org.example.project.tse.operations.admin.users

import com.secureflashcard.wormapi.WormUserId
import io.ktor.http.HttpStatusCode
import org.example.project.core.error.PosErrorCode
import org.example.project.infrastructure.errors.PosException
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.tse.operations.admin.BaseTseOperation
import org.example.project.tse.models.TseStatus

/**
 * Unblock a user with PUK.
 *
 * If a user enters the wrong PIN too many times, the account is blocked.
 * This operation unblocks the user using the PUK (Personal Unblocking Key)
 * and sets a new PIN.
 *
 * Supported Users:
 * - ADMIN: Administrator user
 * - TIME_ADMIN: Time administrator user
 *
 * Process:
 * 1. Provide the correct PUK
 * 2. Provide a new PIN to set
 * 3. User is unblocked with new PIN
 *
 * WormAPI Method: user_unblock() - line 972
 *
 * Endpoint: POST /admin/users/unblock
 *
 * @param adminPin Admin PIN for TSE authentication (not used for unblock operation)
 * @param userId User to unblock (ADMIN or TIME_ADMIN)
 * @param puk Personal Unblocking Key
 * @param newPin New PIN to set after unblock
 * @since 2.0.0
 */
class UserUnblockOperation(
    adminPin: String,
    private val userId: String = "ADMIN",
    private val puk: String,
    private val newPin: String
) : BaseTseOperation<TseDto.AdminResultDto>(adminPin) {

    init {
        // Enforced here rather than only in [fromParameters], because that is what went wrong
        // before: the guard lived in a factory nothing called, while the live route built this
        // class directly and supplied `?: "123456"` itself. A PUK attempt is one of very few
        // the device will ever grant, and running out is unrecoverable — so the class refuses
        // to exist without a real one, whichever way it is constructed.
        if (puk.isBlank()) {
            throw PosException(
                code = PosErrorCode.TSE_INVALID_PARAMETERS,
                httpStatus = HttpStatusCode.BadRequest,
                message = "A PUK is required to unblock a TSE user.",
            )
        }
        if (newPin.isBlank()) {
            throw PosException(
                code = PosErrorCode.TSE_INVALID_PARAMETERS,
                httpStatus = HttpStatusCode.BadRequest,
                message = "A new PIN is required to unblock a TSE user.",
            )
        }
    }

    /**
     * Unblock user with PUK.
     *
     * @param tseStatus Current TSE status and configuration
     * @return Result.Success with unblock confirmation or Result.Error
     */
    override fun performOperation(tseStatus: TseStatus): TseDto.AdminResultDto {
        logger.info("Unblocking user: $userId")

        val store = getWormStore(tseStatus)

        val wormUserId = when (userId.uppercase()) {
            "ADMIN" -> WormUserId.WORM_USER_ADMIN
            "TIME_ADMIN" -> WormUserId.WORM_USER_TIME_ADMIN
            else -> WormUserId.WORM_USER_ADMIN
        }

        store.user_unblock(
            wormUserId,
            puk.toByteArray(Charsets.UTF_8),
            newPin.toByteArray(Charsets.UTF_8)
        )

        logger.info("User unblocked successfully: $userId")

        return TseDto.AdminResultDto(
            success = true,
            message = "User $userId unblocked successfully"
        )
    }

    companion object {
        /**
         * Create UserUnblockOperation from request parameters map.
         *
         * @param adminPin Admin PIN
         * @param parameters Map containing userId, puk, newPin
         * @return Result with operation or error
         */
        fun fromParameters(
            adminPin: String,
            parameters: Map<String, String>
        ): UserUnblockOperation {
            val userId = parameters["userId"] ?: "ADMIN"

            // No default. This was `?: "123456"` — the Swissbit development-sample PUK. On
            // production hardware that silently attempts a wrong PUK, and PUK attempts are
            // limited: enough of them block the PUK itself, which is the credential of last
            // resort for a blocked PIN.
            val puk = parameters["puk"] ?: throw PosException(
                code = PosErrorCode.TSE_INVALID_PARAMETERS,
                httpStatus = HttpStatusCode.BadRequest,
                message = "A PUK is required to unblock a TSE user.",
            )

            val newPin = parameters["newPin"] ?: adminPin

            return UserUnblockOperation(
                adminPin = adminPin,
                userId = userId,
                puk = puk,
                newPin = newPin
            )
        }
    }
}
