package org.example.project.core.network.error

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import org.example.project.core.error.PosErrorCode
import org.example.project.core.presentation.error.UiError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunCatchingApiTest {
    @Test
    fun `success returns Result success and onError is not invoked`() =
        runTest {
            var captured: UiError? = null
            val result =
                runCatchingApi { 42 }
                    .onError { captured = it }

            assertEquals(42, result.getOrNull())
            assertNull(captured)
        }

    @Test
    fun `PosApiException maps to UiError FromServer`() =
        runTest {
            val ex =
                PosApiException(
                    code = PosErrorCode.EMPLOYEE_NOT_FOUND,
                    httpStatus = 404,
                    message = "employee 42 not found",
                    traceId = "trace-abc",
                )
            var captured: UiError? = null
            runCatchingApi<Int> { throw ex }
                .onError { captured = it }

            val fromServer = captured as? UiError.FromServer
            assertNotNull(fromServer)
            assertEquals(PosErrorCode.EMPLOYEE_NOT_FOUND, fromServer.code)
            assertEquals(404, fromServer.httpStatus)
            assertEquals("trace-abc", fromServer.traceId)
        }

    @Test
    fun `NetworkApiException maps to UiError Network`() =
        runTest {
            var captured: UiError? = null
            runCatchingApi<Int> { throw NetworkApiException(IOException("offline")) }
                .onError { captured = it }

            assertTrue(captured is UiError.Network)
        }

    @Test
    fun `CancellationException is rethrown and not swallowed`() =
        runTest {
            assertFailsWith<CancellationException> {
                runCatchingApi<Int> { throw CancellationException("cancel") }
            }
        }
}
