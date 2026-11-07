package org.example.project.security

import io.ktor.http.HttpMethod
import org.example.project.infrastructure.security.TransactionalRoutes
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A4 Task 3: pins [TransactionalRoutes] against every real TSE `signTransaction`/`enqueue` call
 * site that signs under the CALLING TERMINAL's own client, as of this commit — verified by
 * tracing each such sign call up to its route:
 *  - `OrderServiceImpl.createOrder`          -> POST /api/orders
 *  - `OrderItemServiceImpl.cancelOrderItems` -> POST /api/items/cancel/{sourceTableId}
 *  - `BillServiceImpl.createBill`            -> POST /api/v1/bills
 *  - `BillServiceImpl.createBill` (2nd caller, `CardPaymentSseRoutes`) -> GET /api/bills/create-with-card
 *  - `BillServiceImpl.deleteBill`            -> DELETE /api/v1/bills/{id}
 *  - `BillServiceImpl.rebookBill`            -> POST /api/v1/bills/{id}/rebook
 *
 * `ZBonServiceImpl.createReport` and `KassenbuchServiceImpl` also sign, but are intentionally
 * excluded — see the doc comment on [TransactionalRoutes] for why.
 *
 * Update this test whenever a new per-terminal sign call site is added.
 */
@DisplayName("TransactionalRoutes (A4 Task 3)")
class TransactionalRoutesTest {
    @Test
    fun `POST api orders is transactional`() {
        assertTrue(TransactionalRoutes.isTransactional("/api/orders", HttpMethod.Post))
    }

    @Test
    fun `POST api items cancel sourceTableId is transactional`() {
        assertTrue(TransactionalRoutes.isTransactional("/api/items/cancel/42", HttpMethod.Post))
    }

    @Test
    fun `POST api v1 bills is transactional`() {
        assertTrue(TransactionalRoutes.isTransactional("/api/v1/bills", HttpMethod.Post))
    }

    @Test
    fun `GET api bills create-with-card is transactional (2nd createBill caller, distinct from api v1 bills)`() {
        assertTrue(TransactionalRoutes.isTransactional("/api/bills/create-with-card", HttpMethod.Get))
    }

    @Test
    fun `DELETE api v1 bills id is transactional`() {
        assertTrue(TransactionalRoutes.isTransactional("/api/v1/bills/BILL-1", HttpMethod.Delete))
    }

    @Test
    fun `POST api v1 bills id rebook is transactional`() {
        assertTrue(TransactionalRoutes.isTransactional("/api/v1/bills/BILL-1/rebook", HttpMethod.Post))
    }

    @Test
    fun `POST api v1 terminals session is transactional (it mints the credential the others run under)`() {
        assertTrue(TransactionalRoutes.isTransactional("/api/v1/terminals/session", HttpMethod.Post))
    }

    @Test
    fun `POST api v1 bills id print is NOT transactional`() {
        assertFalse(TransactionalRoutes.isTransactional("/api/v1/bills/BILL-1/print", HttpMethod.Post))
    }

    @Test
    fun `POST api orders reference-print is NOT transactional`() {
        assertFalse(TransactionalRoutes.isTransactional("/api/orders/reference-print", HttpMethod.Post))
    }

    @Test
    fun `GET api orders is NOT transactional (method mismatch)`() {
        assertFalse(TransactionalRoutes.isTransactional("/api/orders", HttpMethod.Get))
    }

    @Test
    fun `GET api v1 bills id is NOT transactional (method mismatch, read not delete)`() {
        assertFalse(TransactionalRoutes.isTransactional("/api/v1/bills/BILL-1", HttpMethod.Get))
    }

    @Test
    fun `unrelated route is NOT transactional`() {
        assertFalse(TransactionalRoutes.isTransactional("/api/v1/terminals", HttpMethod.Get))
    }
}
