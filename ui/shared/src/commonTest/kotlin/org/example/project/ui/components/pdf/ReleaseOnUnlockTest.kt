package org.example.project.ui.components.pdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** The hand-off rule, driven single-threaded: "held" is a flag, so close() can run while work holds the lock. */
class ReleaseOnUnlockTest {
    private class Harness {
        var held = false
        var closed = false
        var releases = 0
        var releasedWhileUnlocked = false
        var releaseFailure: Exception? = null

        val subject =
            ReleaseOnUnlock(
                lock = {
                    check(!held) { "the fake lock is not reentrant" }
                    held = true
                },
                tryLock = { if (held) false else true.also { held = true } },
                unlock = { held = false },
                isClosed = { closed },
                release = {
                    if (!held) releasedWhileUnlocked = true
                    releases++
                    releaseFailure?.let { throw it }
                },
            )

        fun close() {
            closed = true
            subject.releaseIfClosed()
        }
    }

    @Test
    fun closeWithTheLockFreeReleasesAtOnce() {
        val harness = Harness()
        harness.close()
        assertEquals(1, harness.releases)
        assertFalse(harness.held, "the lock is let go after the release")
    }

    @Test
    fun closeWhileTheLockIsHeldLeavesTheReleaseToTheHolder() {
        val harness = Harness()
        harness.subject.withDocumentLock {
            harness.close()
            assertEquals(0, harness.releases, "nothing may be freed under the work holding the lock")
        }
        assertEquals(1, harness.releases, "the holder releases as it lets go")
        assertFalse(harness.releasedWhileUnlocked, "the release always runs under the lock")
        assertFalse(harness.held)
    }

    @Test
    fun workWithoutACloseReleasesNothing() {
        val harness = Harness()
        assertEquals(7, harness.subject.withDocumentLock { 7 })
        assertEquals(0, harness.releases)
    }

    @Test
    fun theHolderReleasesEvenWhenItsWorkFails() {
        val harness = Harness()
        assertFailsWith<IllegalStateException> {
            harness.subject.withDocumentLock {
                harness.close()
                error("render failed")
            }
        }
        assertEquals(1, harness.releases)
    }

    @Test
    fun aFailingReleaseNeitherEscapesNorKeepsTheLock() {
        val harness = Harness()
        harness.releaseFailure = IllegalStateException("close failed")
        assertEquals(3, harness.subject.withDocumentLock { harness.close().let { 3 } })
        harness.close()
        assertFalse(harness.held, "a failed release must still let go of the lock")
    }
}
