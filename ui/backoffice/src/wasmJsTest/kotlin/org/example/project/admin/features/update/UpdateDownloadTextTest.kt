package org.example.project.admin.features.update

import org.example.project.admin.features.update.presentation.downloadPercent
import org.example.project.admin.features.update.presentation.formatDownloadSize
import org.example.project.core.domain.dto.update.UpdateDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdateDownloadTextTest {
    private fun progress(
        done: Long,
        total: Long?,
    ) = UpdateDto.DownloadProgressDto(UpdateDto.DownloadPhase.SERVER, done, total)

    @Test
    fun `sizes read in the unit an operator expects`() {
        assertEquals("0 B", formatDownloadSize(0))
        assertEquals("812 B", formatDownloadSize(812))
        assertEquals("2 KB", formatDownloadSize(2_048))
        assertEquals("1.0 MB", formatDownloadSize(1_048_576))
        assertEquals("9.9 MB", formatDownloadSize(10_406_942))
        assertEquals("110.5 MB", formatDownloadSize(115_873_254))
    }

    @Test
    fun `a negative size never renders as a negative number`() {
        assertEquals("0 B", formatDownloadSize(-5))
    }

    @Test
    fun `percent is whole and clamped`() {
        assertEquals(0, downloadPercent(progress(0, 200)))
        assertEquals(50, downloadPercent(progress(100, 200)))
        assertEquals(99, downloadPercent(progress(199, 200)))
        assertEquals(100, downloadPercent(progress(200, 200)))
        assertEquals(100, downloadPercent(progress(300, 200)))
    }

    @Test
    fun `an unknown or empty total has no percentage`() {
        assertNull(downloadPercent(progress(10, null)))
        assertNull(downloadPercent(progress(10, 0)))
    }
}
