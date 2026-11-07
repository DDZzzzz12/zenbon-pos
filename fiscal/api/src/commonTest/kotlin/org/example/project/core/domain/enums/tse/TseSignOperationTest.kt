package org.example.project.core.domain.enums.tse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The enum is the single mapping from a DSFinV-K processType to the driver command, and
 * `BaseTseSignOperation` reads the processType it actually signs back off the RESOLVED entry
 * (`val processType = signCommand.processType`) — not off the caller's string.
 *
 * That makes two properties fiscally load-bearing, and both are asserted here:
 *  1. an unrecognised processType must be REJECTED, never silently resolved — otherwise the TSE
 *     signs `Kassenbeleg-V1` while the database records whatever was requested;
 *  2. no entry may declare a processType outside the three Anhang I values.
 */
class TseSignOperationTest {
    @Test
    fun order_process_type_maps_to_sign_orders() {
        assertEquals(
            TseSignOperation.SIGN_ORDERS.op,
            TseSignOperation.commandForProcessType("Bestellung-V1"),
        )
    }

    @Test
    fun receipt_process_type_maps_to_sign_bill() {
        assertEquals(
            TseSignOperation.SIGN_BILL.op,
            TseSignOperation.commandForProcessType("Kassenbeleg-V1"),
        )
    }

    @Test
    fun other_process_type_maps_to_sign_free_content_not_bill() {
        // Regression: "SonstigerVorgang" (Z-Bon / Kassenbuch) previously collapsed
        // into SIGN_BILL and was fiscally signed as a Kassenbeleg-V1.
        assertEquals(
            TseSignOperation.SIGN_FREE_CONTENT.op,
            TseSignOperation.commandForProcessType("SonstigerVorgang"),
        )
    }

    @Test
    fun unknown_process_type_is_rejected_not_silently_signed_as_a_receipt() {
        // REPLACES `unknown_process_type_falls_back_to_sign_bill`, which pinned the defect: the
        // fallback `?: SIGN_BILL.op` meant an unrecognised type silently resolved to signbill, and
        // because BaseTseSignOperation takes the signed processType from the RESOLVED entry, the
        // TSE would sign Kassenbeleg-V1 while the DB recorded the requested type. A signature that
        // claims the wrong Vorgangstyp cannot be corrected once it is in the WORM, so the only safe
        // answer to "I don't know this type" is to refuse.
        assertFailsWith<IllegalArgumentException> {
            TseSignOperation.commandForProcessType("NichtVorhanden")
        }
        Unit
    }

    @Test
    fun no_entry_declares_a_process_type_outside_Anhang_I() {
        // REPLACES `cancellation_process_type_maps_to_sign_cancellation`. "Kassenbeleg-Storno" is
        // not an Anhang I processType — a storno is an ordinary Kassenbeleg-V1 with negative
        // amounts, which is what ProcessDataBuilder emits. "AVSonstige" is a BON_TYP, not a
        // processType. Both entries were removed.
        val allowed = setOf("Bestellung-V1", "Kassenbeleg-V1", "SonstigerVorgang")
        val offenders = TseSignOperation.entries.filterNot { it.processType in allowed }
        assertTrue(
            offenders.isEmpty(),
            "these entries declare a processType Anhang I does not define: " +
                offenders.joinToString { "${'$'}{it.name}->${'$'}{it.processType}" },
        )
    }

    @Test
    fun every_ProcessType_has_a_command_so_strictness_can_never_fire_in_production() {
        // `commandForProcessType` now THROWS on an unknown type (correctly — see above). That makes
        // a missing entry here a poison pill rather than a silent mis-sign: the failure would reach
        // the signing queue, which enqueues any Failed outcome under TseFailureBehavior.QUEUE and
        // retries it forever, since the processType can never become valid.
        //
        // So the two enums must stay in lockstep. Asserting it here means the mistake cannot be
        // shipped, and the runtime throw stays a safety net that never fires in production.
        ProcessType.entries.forEach { type ->
            val command = TseSignOperation.commandForProcessType(type.value)
            assertEquals(
                type.value,
                TseSignOperation.getProcessType(command),
                "ProcessType.${'$'}{type.name} ('${'$'}{type.value}') must resolve to a command that maps back to it",
            )
        }
    }

    @Test
    fun command_and_process_type_round_trip_through_the_enum() {
        // Every command an operation emits must parse back to the process type it was chosen for.
        for (processType in listOf("Bestellung-V1", "Kassenbeleg-V1", "SonstigerVorgang")) {
            val command = TseSignOperation.commandForProcessType(processType)
            assertEquals(processType, TseSignOperation.getProcessType(command))
        }
    }
}
