package org.example.project.core.data.cache

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import kotlin.test.Test
import kotlin.test.assertEquals

class CacheChangeBusTest {
    @Test
    fun `emitted change is received by a collector`() =
        runTest {
            val bus = CacheChangeBus()
            val received = mutableListOf<CacheChange>()
            val job = launch { bus.changes.collect { received += it } }
            runCurrent()
            bus.emit(CacheChange(ChangeEntityType.RTABLE, locationTypeId = 1))
            runCurrent()
            job.cancel()
            assertEquals(listOf(CacheChange(ChangeEntityType.RTABLE, 1)), received)
        }
}
