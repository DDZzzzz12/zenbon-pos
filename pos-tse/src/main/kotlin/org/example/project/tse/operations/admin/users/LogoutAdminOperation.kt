package org.example.project.tse.operations.admin.users

import com.secureflashcard.wormapi.WormException
import com.secureflashcard.wormapi.WormUserId
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.tse.models.TseStatus
import org.example.project.tse.operations.admin.BaseTseOperation

/**
 * Log the Admin user out of the TSE.
 *
 * The counterpart to leaving an Admin session open for the duration of an operator's sitting:
 * when that sitting ends (explicit lock, or idle expiry), the server calls this so the device
 * does not keep Admin logged in. It matters most on TSE 1.x, which has no device-side idle
 * logout at all, and on TSE 2.0 once maintenance is reusing the Admin login (which keeps
 * resetting the 40-minute device timer).
 *
 * Takes no credential and is idempotent: logging out when nobody is logged in is a success,
 * not an error, so the caller never has to reason about the current device state.
 */
class LogoutAdminOperation : BaseTseOperation<TseDto.AdminResultDto>(adminPin = "") {

    override fun performOperation(tseStatus: TseStatus): TseDto.AdminResultDto {
        val store = getWormStore(tseStatus)
        return try {
            store.user_logout(WormUserId.WORM_USER_ADMIN)
            logger.info("Admin logged out of the TSE")
            TseDto.AdminResultDto(success = true, message = "Admin logged out")
        } catch (e: WormException) {
            // Already logged out (e.g. the 2.0 timer got there first, or nobody was logged in)
            // is the desired end state, not a failure.
            logger.debug("Admin logout was a no-op: ${e.message}")
            TseDto.AdminResultDto(success = true, message = "Admin already logged out")
        }
    }
}
