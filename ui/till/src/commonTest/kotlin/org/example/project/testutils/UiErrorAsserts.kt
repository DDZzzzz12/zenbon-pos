package org.example.project.testutils

import org.example.project.core.error.PosErrorCode
import org.example.project.core.presentation.error.UiError
import kotlin.test.assertEquals

/**
 * Asserts the receiver is a [UiError.FromServer] with the expected [PosErrorCode]
 * and returns it for further field-level assertions.
 *
 * Used by Stage C VM tests: `vm.error.value.assertFromServer(PosErrorCode.X)`.
 */
fun UiError?.assertFromServer(expectedCode: PosErrorCode): UiError.FromServer {
    val e =
        this as? UiError.FromServer
            ?: error("expected UiError.FromServer, got $this")
    assertEquals(expectedCode, e.code)
    return e
}
