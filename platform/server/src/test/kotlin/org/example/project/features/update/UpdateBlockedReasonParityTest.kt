package org.example.project.features.update

import org.example.project.core.domain.enums.update.UpdateBlockedReason
import org.example.project.features.update.domain.models.PreflightVerdict
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The 409 from `POST /api/update/apply` carries `PreflightVerdict.Reason.name` as a `FieldError`,
 * and the clients read it back into [UpdateBlockedReason] to pick a localized sentence.
 *
 * `PreflightVerdict` is a pos-server type the clients cannot see, so the two enums are the wire
 * contract and nothing but this test holds them together. Add a blocker on one side only and the
 * screen silently falls back to "the till is not ready right now" — true, but not the one thing the
 * operator needed to be told.
 */
@DisplayName("UpdateBlockedReason / PreflightVerdict.Reason parity")
class UpdateBlockedReasonParityTest {
    @Test
    fun `both enums name the same blockers in the same order`() {
        assertEquals(
            PreflightVerdict.Reason.entries.map { it.name },
            UpdateBlockedReason.entries.map { it.name },
        )
    }
}
