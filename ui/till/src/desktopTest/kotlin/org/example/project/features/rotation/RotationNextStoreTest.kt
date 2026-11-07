package org.example.project.features.rotation

import org.example.project.core.domain.dto.rotation.RotationDto
import org.example.project.core.domain.dto.websocket.NotificationDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RotationNextStoreTest {
    private fun ref(id: String) = RotationDto.EmployeeRefDto(employeeId = id, employeeNumber = id, displayName = id)

    private fun update(
        version: Long,
        next: RotationDto.EmployeeRefDto?,
        snapshot: Boolean = false,
    ) = NotificationDto.RotationNextUpdateDto(timestamp = "", version = version, next = next, isSnapshot = snapshot)

    @Test
    fun newerVersionReplaces() {
        val store = RotationNextStore()
        store.apply(update(1, ref("a")))
        store.apply(update(2, ref("b")))
        assertEquals(ref("b"), store.next.value)
    }

    @Test
    fun olderOrEqualVersionIsDropped() {
        val store = RotationNextStore()
        store.apply(update(5, ref("a")))
        store.apply(update(5, ref("b")))
        store.apply(update(4, ref("c")))
        assertEquals(ref("a"), store.next.value)
    }

    @Test
    fun postRestartSnapshotWithHigherClockSeededVersionIsAccepted() {
        val store = RotationNextStore()
        // Before the restart the server counted up from its boot time; after it, from a later one.
        store.apply(update(1_700_000_000_057, ref("a")))
        store.apply(update(1_700_000_900_000, ref("b"), snapshot = true))
        assertEquals(ref("b"), store.next.value)
        store.apply(update(1_700_000_900_001, ref("c")))
        assertEquals(ref("c"), store.next.value)
    }

    @Test
    fun snapshotOlderThanTheBaselineIsIgnored() {
        val store = RotationNextStore()
        // A reconcile GET answered at version 8 that arrives after the live event at version 9.
        store.apply(update(9, ref("a")))
        store.apply(update(8, ref("stale"), snapshot = true))
        assertEquals(ref("a"), store.next.value)
    }

    @Test
    fun snapshotAtTheBaselineVersionIsAccepted() {
        val store = RotationNextStore()
        store.apply(update(9, ref("a")))
        store.apply(update(9, ref("a"), snapshot = true))
        assertEquals(ref("a"), store.next.value)
        store.apply(update(10, ref("b")))
        assertEquals(ref("b"), store.next.value)
    }

    @Test
    fun firstSnapshotWithNoBaselineIsAccepted() {
        val store = RotationNextStore()
        store.apply(update(3, ref("a"), snapshot = true))
        assertEquals(ref("a"), store.next.value)
    }

    @Test
    fun nullNextClearsChip() {
        val store = RotationNextStore()
        store.apply(update(1, ref("a")))
        store.apply(update(2, null))
        assertNull(store.next.value)
    }
}
