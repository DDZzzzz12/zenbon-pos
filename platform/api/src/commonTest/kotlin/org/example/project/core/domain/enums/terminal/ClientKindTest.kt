package org.example.project.core.domain.enums.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

class ClientKindTest {
    @Test
    fun `unknown or blank code is NATIVE`() {
        assertEquals(ClientKind.NATIVE, ClientKind.fromCode(null))
        assertEquals(ClientKind.NATIVE, ClientKind.fromCode(""))
        assertEquals(ClientKind.NATIVE, ClientKind.fromCode("toaster"))
    }

    @Test
    fun `code is trimmed and case-insensitive`() {
        assertEquals(ClientKind.BROWSER, ClientKind.fromCode(" browser "))
    }

    @Test
    fun `a pos-preview device id is BROWSER even when the client claims NATIVE`() {
        assertEquals(ClientKind.BROWSER, ClientKind.effective("NATIVE", "pos-preview-abc123"))
        assertEquals(ClientKind.NATIVE, ClientKind.effective("NATIVE", "android-1"))
        assertEquals(ClientKind.BROWSER, ClientKind.effective("BROWSER", "android-1"))
    }
}
