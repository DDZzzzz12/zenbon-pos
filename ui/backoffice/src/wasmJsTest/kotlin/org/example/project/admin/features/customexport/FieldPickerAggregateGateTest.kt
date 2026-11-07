package org.example.project.admin.features.customexport

import org.example.project.admin.features.customexport.presentation.components.chosenAggregateCount
import org.example.project.admin.features.customexport.presentation.components.lockedAggregateKeys
import org.example.project.core.domain.dto.customexport.CustomExportDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The picker's aggregate gate, tested where it can be tested.
 *
 * `FieldPicker` renders to a canvas the wasm test leg cannot read, so the part that has to be right —
 * *which* checkboxes are refused, given what is ticked and what the server's cap is — lives in
 * `lockedAggregateKeys` and is asserted on a plain set, exactly as `groupFields` is.
 *
 * The failure being guarded against is not a crash: an over-wide definition ticks, saves, and is
 * refused by the server. Every assertion below is therefore about a boundary, not about rendering.
 */
class FieldPickerAggregateGateTest {
    private fun column(key: String) = CustomExportDto.FieldDto(key = key, label = key, type = "TEXT")

    private fun aggregate(key: String) = CustomExportDto.FieldDto(key, key, "NUMBER", "Rechnungen", isAggregate = true)

    private val available =
        listOf(
            column("partner.name"),
            column("partner.city"),
            aggregate("invoice~count"),
            aggregate("invoice~sum"),
            aggregate("invoice~first"),
        )

    @Test
    fun `under the cap nothing is locked`() {
        assertEquals(
            emptySet(),
            lockedAggregateKeys(available, chosenKeys = setOf("invoice~count"), maxAggregateColumns = 3),
            "one summary column of three leaves two to tick, so no checkbox may be refused",
        )
    }

    @Test
    fun `at the cap the unchosen aggregates are locked and the chosen ones are not`() {
        val locked =
            lockedAggregateKeys(
                available,
                chosenKeys = setOf("partner.name", "invoice~count", "invoice~sum"),
                maxAggregateColumns = 2,
            )

        assertEquals(
            setOf("invoice~first"),
            locked,
            "only the summary column that would be the third is refused",
        )
        assertTrue(
            "invoice~count" !in locked && "invoice~sum" !in locked,
            "a chosen aggregate must stay tickable-off — locking it would leave the operator at the " +
                "cap with no control that gets them back under it",
        )
    }

    /**
     * Ordinary columns cost nothing per row and are uncapped on the server too. If the gate ever
     * counted or locked plain columns, this is where it would show.
     */
    @Test
    fun `ordinary columns are never locked, at any cap`() {
        val locked = lockedAggregateKeys(available, chosenKeys = emptySet(), maxAggregateColumns = 0)

        assertTrue(
            "partner.name" !in locked && "partner.city" !in locked,
            "a plain column stays tickable even under a cap of zero",
        )
    }

    @Test
    fun `a cap of zero locks every aggregate`() {
        assertEquals(
            setOf("invoice~count", "invoice~sum", "invoice~first"),
            lockedAggregateKeys(available, chosenKeys = emptySet(), maxAggregateColumns = 0),
            "no summary column may be ticked when none is allowed",
        )
    }

    /** The number the hint reports counts summary columns only, not the whole selection. */
    @Test
    fun `the hint counts chosen aggregates, not chosen columns`() {
        assertEquals(
            1,
            chosenAggregateCount(available, chosenKeys = setOf("partner.name", "partner.city", "invoice~sum")),
        )
    }
}
