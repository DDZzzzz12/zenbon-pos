package org.example.project.core.domain.dto.tse

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * T5-6: the time-filtered TSE export used to convert the operator's days on the client, in the
 * device's time zone, and sent the END day's midnight — so the end day was dropped. The day form
 * is now resolved in the shop zone as the whole of both days, the period DSFinV-K uses.
 */
class TimeFilteredExportRangeTest {
    private val berlin = TimeZone.of("Europe/Berlin")

    private fun millis(
        dateTime: String,
        zone: TimeZone,
    ): Long = LocalDateTime.parse(dateTime).toInstant(zone).toEpochMilliseconds()

    @Test
    fun aDayRangeCoversBothDaysInFullInTheShopZone() {
        val (start, end) =
            TseDto
                .TimeFilteredExportRequestDto(startDay = "2026-09-01", endDay = "2026-09-30")
                .resolveEpochMillis(berlin)

        assertEquals(millis("2026-09-01T00:00", berlin), start)
        assertEquals(millis("2026-10-01T00:00", berlin) - 1, end)
    }

    @Test
    fun aSingleDayIsTheWholeDayNotItsFirstMillisecond() {
        val (start, end) =
            TseDto
                .TimeFilteredExportRequestDto(startDay = "2026-09-24", endDay = "2026-09-24")
                .resolveEpochMillis(berlin)

        assertEquals(24 * 60 * 60 * 1000L - 1, end - start)
    }

    @Test
    fun theShopZoneNotUtcDecidesWhereTheDayStarts() {
        val (start, _) =
            TseDto
                .TimeFilteredExportRequestDto(startDay = "2026-07-01", endDay = "2026-07-01")
                .resolveEpochMillis(berlin)

        // CEST is UTC+2: Berlin's midnight is 22:00 UTC the evening before.
        assertEquals(millis("2026-06-30T22:00", TimeZone.UTC), start)
    }

    @Test
    fun aDaylightSavingDayIsItsRealLength() {
        val (start, end) =
            TseDto
                .TimeFilteredExportRequestDto(startDay = "2026-03-29", endDay = "2026-03-29")
                .resolveEpochMillis(berlin)

        assertEquals(23 * 60 * 60 * 1000L - 1, end - start)
    }

    @Test
    fun theInstantFormPassesThroughUnchanged() {
        val range =
            TseDto
                .TimeFilteredExportRequestDto(startDate = 1_000L, endDate = 2_000L)
                .resolveEpochMillis(berlin)

        assertEquals(1_000L to 2_000L, range)
    }

    @Test
    fun anOlderClientsInstantPayloadStillDecodes() {
        val dto =
            Json.decodeFromString<TseDto.TimeFilteredExportRequestDto>("""{"startDate":1000,"endDate":2000}""")

        assertEquals(1_000L to 2_000L, dto.resolveEpochMillis(berlin))
    }

    @Test
    fun malformedOrAmbiguousRequestsAreRefused() {
        val refused =
            listOf(
                TseDto.TimeFilteredExportRequestDto(),
                TseDto.TimeFilteredExportRequestDto(startDay = "2026-09-01"),
                TseDto.TimeFilteredExportRequestDto(startDate = 1_000L),
                TseDto.TimeFilteredExportRequestDto(startDay = "2026-09-01", endDay = "2026-09-02", startDate = 1L, endDate = 2L),
                TseDto.TimeFilteredExportRequestDto(startDay = "01.09.2026", endDay = "2026-09-02"),
                TseDto.TimeFilteredExportRequestDto(startDay = "2026-09-02", endDay = "2026-09-01"),
                TseDto.TimeFilteredExportRequestDto(startDate = 2_000L, endDate = 1_000L),
            )

        refused.forEach { request ->
            assertFailsWith<IllegalArgumentException>(request.toString()) { request.resolveEpochMillis(berlin) }
        }
    }
}
