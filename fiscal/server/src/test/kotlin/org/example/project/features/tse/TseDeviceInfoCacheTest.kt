package org.example.project.features.tse

import org.example.project.features.tse.data.service.TseDeviceInfoCache
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * m3: the log-time-format cache the admin status path fills and the signing persist reads.
 *
 * The invariant that matters fiscally: a transient status read that returns null/blank must NOT
 * erase a previously observed format, or a signature taken right after such a read would record no
 * format and the export would silently fall back — losing a format the device HAD reported.
 */
@DisplayName("TseDeviceInfoCache keeps the last known-good log-time format (m3)")
class TseDeviceInfoCacheTest {
    @Test
    fun `a real format is recorded`() {
        TseDeviceInfoCache.recordLogTimeFormat("utcTimeWithSeconds")
        assertEquals("utcTimeWithSeconds", TseDeviceInfoCache.logTimeFormat)
    }

    @Test
    fun `null and blank observations do not erase a known-good value`() {
        TseDeviceInfoCache.recordLogTimeFormat("unixTime")
        TseDeviceInfoCache.recordLogTimeFormat(null)
        TseDeviceInfoCache.recordLogTimeFormat("   ")
        assertEquals(
            "unixTime",
            TseDeviceInfoCache.logTimeFormat,
            "a transient empty status must not wipe a format the device already reported",
        )
    }

    @Test
    fun `a later real observation replaces an earlier one`() {
        TseDeviceInfoCache.recordLogTimeFormat("unixTime")
        TseDeviceInfoCache.recordLogTimeFormat("generalizedTime")
        assertEquals("generalizedTime", TseDeviceInfoCache.logTimeFormat)
    }
}
