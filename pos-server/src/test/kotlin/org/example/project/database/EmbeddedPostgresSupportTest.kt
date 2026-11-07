package org.example.project.database

import org.example.project.utils.OSType
import org.junit.jupiter.api.DisplayName
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DisplayName("Embedded PostgreSQL architecture support")
class EmbeddedPostgresSupportTest {
    @Test
    fun `linux x86_64 is supported`() {
        assertTrue(EmbeddedPostgresSupport.isSupported("x86_64", OSType.LINUX))
    }

    @Test
    fun `linux amd64 is supported — the same architecture under another name`() {
        assertTrue(EmbeddedPostgresSupport.isSupported("amd64", OSType.LINUX))
    }

    @Test
    fun `linux arm64 is not supported`() {
        assertFalse(EmbeddedPostgresSupport.isSupported("aarch64", OSType.LINUX))
        assertFalse(EmbeddedPostgresSupport.isSupported("arm64", OSType.LINUX))
    }

    @Test
    @DisplayName("macOS on Apple Silicon stays supported — Rosetta runs the x86_64 postgres")
    fun `apple silicon is not blocked`() {
        // OsUtils maps macOS to UNKNOWN. This is the case that must never regress: gating on
        // architecture alone would break every Apple Silicon development machine, where the
        // bundled darwin-x86_64 binary runs fine under Rosetta.
        assertTrue(EmbeddedPostgresSupport.isSupported("aarch64", OSType.UNKNOWN))
    }

    @Test
    fun `windows on arm is not blocked either`() {
        assertTrue(EmbeddedPostgresSupport.isSupported("aarch64", OSType.WINDOWS))
    }

    @Test
    @DisplayName("the message names the architecture, the way out, and the TSE constraint")
    fun `the message is actionable`() {
        val m = EmbeddedPostgresSupport.unsupportedMessage("aarch64")

        assertTrue(m.contains("aarch64"), "must name the architecture the operator is on: $m")
        assertTrue(m.contains("DATABASE_MODE=external"), "must give the database way out: $m")
        // The important half. Telling an operator to switch database mode without mentioning the
        // TSE would let them boot happily on ARM and discover at the first sale that they cannot
        // sign a receipt -- a worse failure, found later, on a legal obligation.
        assertTrue(m.contains("TSE"), "must say a fiscal install needs x86_64 regardless: $m")
        assertTrue(m.contains("x86_64"), "must name the architecture that does work: $m")
    }
}
