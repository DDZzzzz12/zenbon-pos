package org.example.project.tse.operations

import org.example.project.core.error.PosErrorCode
import org.example.project.infrastructure.errors.PosException
import org.example.project.tse.operations.admin.users.UserUnblockOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Unblocking must never guess a PUK.
 *
 * The device grants very few PUK attempts and exhausting them is unrecoverable, so a wrong
 * PUK is not a retry — it is a step towards a dead TSE. A previous fix put this guard in
 * `fromParameters`, which nothing called, while the live route constructed the operation
 * directly and supplied `?: "123456"` — the Swissbit development-sample PUK — itself. The
 * guard therefore lives in `init`, where no construction path can miss it, and these tests
 * exercise both paths for exactly that reason.
 */
class UnblockRequiresRealPukTest {

    @Test
    fun `direct construction without a PUK is refused`() {
        val e = assertFailsWith<PosException> {
            UserUnblockOperation(adminPin = "54321", userId = "ADMIN", puk = "", newPin = "11111")
        }
        assertEquals(PosErrorCode.TSE_INVALID_PARAMETERS, e.code)
    }

    @Test
    fun `construction via fromParameters without a PUK is refused`() {
        val e = assertFailsWith<PosException> {
            UserUnblockOperation.fromParameters(
                adminPin = "54321",
                parameters = mapOf("userId" to "ADMIN", "newPin" to "11111"),
            )
        }
        assertEquals(PosErrorCode.TSE_INVALID_PARAMETERS, e.code)
    }

    @Test
    fun `a blank new PIN is refused rather than silently substituted`() {
        // The route used to fall back to `request.pass`, quietly writing the Admin PIN as the
        // unblocked user's new PIN — a one-way write the operator never asked for.
        val e = assertFailsWith<PosException> {
            UserUnblockOperation(adminPin = "54321", userId = "TIME_ADMIN", puk = "888888", newPin = "")
        }
        assertEquals(PosErrorCode.TSE_INVALID_PARAMETERS, e.code)
    }

    @Test
    fun `a fully specified unblock still constructs`() {
        UserUnblockOperation(adminPin = "54321", userId = "ADMIN", puk = "888888", newPin = "11111")
        Unit
    }
}
