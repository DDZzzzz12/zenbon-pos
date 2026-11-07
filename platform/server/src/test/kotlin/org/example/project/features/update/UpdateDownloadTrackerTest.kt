package org.example.project.features.update

import org.example.project.core.domain.dto.update.UpdateDto
import org.example.project.features.update.data.service.UpdateDownloadTracker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdateDownloadTrackerTest {
    @Test
    fun `reports nothing before a run starts and after it is cleared`() {
        val tracker = UpdateDownloadTracker()
        assertNull(tracker.snapshot())

        tracker.start(UpdateDto.DownloadPhase.SERVER, bytesTotal = 100)
        tracker.clear()

        assertNull(tracker.snapshot())
    }

    @Test
    fun `a delta adds up its ranges against the planned total`() {
        val tracker = UpdateDownloadTracker()
        tracker.start(UpdateDto.DownloadPhase.SERVER, bytesTotal = 1_000)

        tracker.advance(300)
        tracker.advance(450)

        assertEquals(UpdateDto.DownloadProgressDto(UpdateDto.DownloadPhase.SERVER, 750, 1_000), tracker.snapshot())
    }

    @Test
    fun `a streamed download replaces the count and learns the total when the response carried one`() {
        val tracker = UpdateDownloadTracker()
        tracker.start(UpdateDto.DownloadPhase.ANDROID)

        tracker.report(bytesDone = 10, bytesTotal = null)
        assertEquals(UpdateDto.DownloadProgressDto(UpdateDto.DownloadPhase.ANDROID, 10, null), tracker.snapshot())

        tracker.report(bytesDone = 20, bytesTotal = 200)
        assertEquals(UpdateDto.DownloadProgressDto(UpdateDto.DownloadPhase.ANDROID, 20, 200), tracker.snapshot())
    }

    @Test
    fun `an unknown or zero total stays unknown, never a divide-by-zero for the client`() {
        val tracker = UpdateDownloadTracker()
        tracker.start(UpdateDto.DownloadPhase.SERVER, bytesTotal = 0)

        assertNull(tracker.snapshot()?.bytesTotal)
    }

    @Test
    fun `progress reported with no phase running is ignored`() {
        val tracker = UpdateDownloadTracker()

        tracker.report(5, 10)
        tracker.advance(5)

        assertNull(tracker.snapshot())
    }

    @Test
    fun `starting a new phase resets the count`() {
        val tracker = UpdateDownloadTracker()
        tracker.start(UpdateDto.DownloadPhase.SERVER, bytesTotal = 100)
        tracker.advance(80)

        tracker.start(UpdateDto.DownloadPhase.ANDROID)

        assertEquals(UpdateDto.DownloadProgressDto(UpdateDto.DownloadPhase.ANDROID, 0, null), tracker.snapshot())
    }
}
