package org.example.project.core.utils

import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Notification rows used to cut `HH:mm` straight out of the server's UTC instant, so a Berlin till
 * showed 22:11 for a billing request made at 00:11.
 */
class NotificationTimeFormatTest {
    /** Berlin in summer, as a fixed offset, so no target needs a time-zone database for this test. */
    private val berlinSummer: TimeZone = UtcOffset(hours = 2).asTimeZone()

    @Test
    fun aUtcInstantIsShownInTheTillsTimeZone() {
        assertEquals("00:11", formatNotificationTime("2026-09-13T22:11:05.123Z", berlinSummer))
    }

    @Test
    fun aClockNowStringWithNanosecondsParses() {
        assertEquals("00:11", formatNotificationTime("2026-09-13T22:11:05.123456789Z", berlinSummer))
    }

    @Test
    fun anInstantWithAnOffsetIsConvertedToo() {
        assertEquals("22:11", formatNotificationTime("2026-09-14T00:11:05+02:00", TimeZone.UTC))
    }

    @Test
    fun epochMillisFromAnOlderServerStillShowTheRightTime() {
        // 1789337465123 ms is 2026-09-13T22:11:05.123Z.
        assertEquals("00:11", formatNotificationTime("1789337465123", berlinSummer))
    }

    @Test
    fun aZonelessLocalDateTimeIsAlreadyLocal() {
        assertEquals("08:05", formatNotificationTime("2026-09-13T08:05:00", berlinSummer))
    }

    @Test
    fun anUnreadableTimestampShowsNoTimeRatherThanThrowing() {
        assertEquals("", formatNotificationTime("not-a-time", berlinSummer))
        assertEquals("", formatNotificationTime("", berlinSummer))
    }
}
