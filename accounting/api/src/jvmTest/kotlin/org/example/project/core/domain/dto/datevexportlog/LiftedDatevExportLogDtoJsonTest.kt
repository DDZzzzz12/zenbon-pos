package org.example.project.core.domain.dto.datevexportlog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the wire form of the unassigned-contra notice (`UnassignedSummary`, `UnassignedBooking`,
 * `CellRef`) inside `DatevExportLogDto`. The notice types were lifted out of
 * `DatevAccountMappingDto`; the nesting is not on the wire. Names only the container.
 */
class LiftedDatevExportLogDtoJsonTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun test_lifted_dtos_json_unchanged_datev_export_log() {
        val log =
            json.decodeFromString(
                DatevExportLogDto.serializer(),
                """{"id":1,"exportType":"KASSENBUCH","periodStart":"a","periodEnd":"b","exportedAt":"c","exportedByUserId":"u","fileHash":"h","fileName":"f","rowCount":2,"entryCount":2,"unassignedContra":{"lineCount":3,"bookings":[{"cell":{"side":"INCOME","kind":"STANDARD","rate":"19","paymentTypeId":null,"paymentTypeName":null,"special":null},"lineCount":2},{"cell":{"paymentTypeId":5,"paymentTypeName":"Karte"},"lineCount":1}]}}""",
            )
        val out = json.parseToJsonElement(json.encodeToString(DatevExportLogDto.serializer(), log)).jsonObject
        assertEquals(
            """{"lineCount":3,"bookings":[{"cell":{"side":"INCOME","kind":"STANDARD","rate":"19","paymentTypeId":null,"paymentTypeName":null,"special":null},"lineCount":2},{"cell":{"side":null,"kind":null,"rate":null,"paymentTypeId":5,"paymentTypeName":"Karte","special":null},"lineCount":1}]}""",
            out["unassignedContra"].toString(),
        )
    }
}
