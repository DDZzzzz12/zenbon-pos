package org.example.project.audit

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.enums.setup.LogOperation
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.features.audit.database.tables.TransactionAuditLogTable
import org.example.project.testutils.TestDatabase
import org.example.project.utils.errorHandling.ExceptionConverter
import org.example.project.utils.log.AuditEntityRef
import org.example.project.utils.log.EntryLogConfig
import org.example.project.utils.log.EntryLogData
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.reflect.KVisibility
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * An audit row must name the entity the operation acted on.
 *
 * Measured on a restored production database, 97 of 100 sampled `transaction_audit_log` rows carry
 * `entityId = "UNKNOWN"`: [ExceptionConverter] resolved the entity by reflecting over the returned
 * DTO for members literally named `id`, `billNumber`, `orderId` or `name`, and yielded nothing
 * whenever the DTO used other names. "Which bill was voided?" was therefore unanswerable.
 *
 * Resolution is now declared by the call site, in two tiers. Reflection is deliberately NOT a third
 * tier — see [fallsBackToUnknown], whose fixture the deleted tier WOULD have resolved.
 */
@DisplayName("Audit rows identify their entity")
class AuditEntityIdentityTest {
    /** A DTO naming its key something reflection never scanned for. */
    private data class Created(
        val billId: Long,
        val number: String,
    )

    /**
     * Named so the deleted reflection tier would have resolved it: [ExceptionConverter]'s
     * `extractEntityInfo` scanned for a member called exactly `id`. The old code wrote `"4711"`
     * into the row from this; the new code must write `UNKNOWN`.
     *
     * **Deliberately NOT `private`.** Kotlin reflection cannot call a member of a private nested
     * class from another module — it throws `IllegalCallableAccessException`, `extractEntityInfo`
     * swallows that and returns all-nulls, and [fallsBackToUnknown] then passes against the OLD
     * code too. Making this private silently defangs the one test that pins "reflection is not a
     * tier"; it was private, and it did.
     */
    data class Reflectable(
        val id: Long,
        val name: String,
    )

    /**
     * Mirrors the shape `BillServiceImpl`'s three audited blocks return: a local holder whose
     * identifiers sit one level down, on a nested signing-data class rather than on the value itself.
     */
    private data class SigningData(
        val billId: Long,
        val billNumber: String,
    )

    /** The outer holder `createBill` returns — the bill's ids are reachable only through it. */
    private data class BillCreation(
        val summary: String,
        val signingData: SigningData,
    )

    /**
     * Mirrors `RTableServiceImpl.transferTable`'s response, whose session id is nullable BY DESIGN:
     * `forItemTransfer` never sets one. See [transferNamesTheSessionItMoved] and
     * [transferWithoutASessionIdStaysUnknown].
     */
    private data class TransferOutcome(
        val transferType: String,
        val sessionId: Long?,
        val message: String,
    )

    /**
     * Mirrors `KassenbuchServiceImpl.createEntry`'s return: the header's own id is reachable only
     * through a list, and the Beleg number is the entry's other key for the same row.
     */
    private data class EntryGroup(
        val belegNr: String,
        val entries: List<EntryProjection>,
    )

    /** One element of [EntryGroup.entries]; its `id` is the `kassenbuch_header` primary key. */
    private data class EntryProjection(
        val id: Long,
    )

    /**
     * Captures what [ExceptionConverter] logs, so the difference between DECLINING to name an entity
     * and FAILING to is assertable and not merely commented.
     *
     * Without this nothing distinguishes the two: both leave the row `UNKNOWN`, so a change that
     * routed the quiet path through the warn branch would break no test while writing a stack trace
     * on every routine item transfer.
     */
    private val logCapture = ListAppender<ILoggingEvent>()

    private val converterLogger get() = LoggerFactory.getLogger(ExceptionConverter::class.java) as Logger

    private fun warningsFromTheConverter(): List<String> =
        logCapture.list
            .filter { it.level.toInt() >= Level.WARN.toInt() }
            .map { it.formattedMessage }

    @BeforeEach
    fun setUp() {
        logCapture.start()
        converterLogger.addAppender(logCapture)
        TestDatabase.setup()
    }

    @AfterEach
    fun tearDown() {
        converterLogger.detachAppender(logCapture)
        logCapture.stop()
        logCapture.list.clear()
        TestDatabase.teardown()
    }

    private fun lastAuditRow() =
        transaction {
            TransactionAuditLogTable
                .selectAll()
                .orderBy(TransactionAuditLogTable.id to SortOrder.DESC)
                .limit(1)
                .single()
        }

    /**
     * Guards the one thing [fallsBackToUnknown] cannot assert about itself: that its fixture is
     * still something the deleted reflection tier WOULD have resolved.
     *
     * That has silently stopped being true twice — first because the fixture's members were named
     * `billId`/`number`, which the tier never scanned for, then because it was `private`, which
     * reflection cannot reach across modules. Both times the test passed against the OLD code. A
     * KDoc cannot fail a build; these two assertions fail at the moment of the edit.
     *
     * Deliberately NOT a `.call()` — invoking the member from inside this class would succeed under
     * nestmate access even while `extractEntityInfo`, in another module, could not reach it, so a
     * call-based oracle would be the very false green it is here to prevent.
     */
    private fun assertFixtureWouldHaveBeenReflected() {
        assertEquals(
            KVisibility.PUBLIC,
            Reflectable::class.visibility,
            "a private fixture is unreflectable across modules — see the Reflectable KDoc",
        )
        assertTrue(
            Reflectable::class.members.any { it.name == "id" },
            "extractEntityInfo scanned for a member named exactly 'id'; renaming it defangs this test",
        )
    }

    @Test
    @DisplayName("the auditEntity lambda names the entity")
    fun auditEntityLambdaWins() =
        runBlocking {
            ExceptionConverter.catchingThrows(
                context = "test",
                employeeId = "tester",
                operation = "CREATE_BILL",
                entityType = "BILL",
                auditEntity = { AuditEntityRef(id = it.billId.toString(), name = it.number) },
                block = { Created(4711L, "RE-260917-369") },
            )

            val row = lastAuditRow()
            assertEquals("4711", row[TransactionAuditLogTable.entityId])
            assertEquals("RE-260917-369", row[TransactionAuditLogTable.entityName])
        }

    @Test
    @DisplayName("with no lambda and no entry-log config the row is honestly UNKNOWN")
    fun fallsBackToUnknown() =
        runBlocking {
            assertFixtureWouldHaveBeenReflected()

            ExceptionConverter.catchingThrows(
                context = "test",
                employeeId = "tester",
                operation = "CREATE_BILL",
                entityType = "BILL",
                block = { Reflectable(4711L, "RE-260917-369") },
            )

            val row = lastAuditRow()
            // The fixture is deliberately reflectable: `Reflectable.id` is exactly what the deleted
            // tier scanned for, so the old code wrote "4711" here. A guess that lands on the wrong
            // field is worse than a visible gap in an audit trail, so nothing is guessed now.
            assertEquals("UNKNOWN", row[TransactionAuditLogTable.entityId])
            assertEquals("Successful operation", row[TransactionAuditLogTable.entityName])
        }

    /**
     * `entity_id` and `entity_name` were written TWICE per row: once into the typed, indexed
     * columns from the call site's lambda, and once into the `metadata` JSON from the reflected
     * `entityInfo` — the very source [fallsBackToUnknown] removed from the columns.
     *
     * The damaging shape is the inverse of the obvious one. At a site with no `auditEntity` lambda
     * whose DTO happens to expose a member named `id`, the column honestly says `UNKNOWN` while the
     * JSON says `4711`: the value the design forbids is still recorded, one field over. It is
     * operator-visible in one glance — `AuditLogScreen` renders the id column and the raw metadata
     * inside the same expanded row, so the two disagree on screen.
     *
     * The columns carry both, typed and indexed. The JSON copy was only ever a duplicate, and is
     * now a duplicate that can disagree, so it is gone.
     */
    @Test
    @DisplayName("metadata carries no entity id that could contradict the column")
    fun metadataDoesNotRestateTheEntityId() =
        runBlocking {
            assertFixtureWouldHaveBeenReflected()

            ExceptionConverter.catchingThrows(
                context = "test",
                employeeId = "tester",
                operation = "CREATE_BILL",
                entityType = "BILL",
                block = { Reflectable(4711L, "RE-260917-369") },
            )

            val row = lastAuditRow()
            assertEquals("UNKNOWN", row[TransactionAuditLogTable.entityId])

            // NOT `.orEmpty()`: that swallows a null column, so setting `this.metadata = null` — or
            // emptying buildSuccessMetadata outright — would keep this green while silently dropping
            // `context` and `note` from every success row.
            val metadata =
                requireNotNull(row[TransactionAuditLogTable.metadata]) {
                    "metadata must still be written; only the entity keys were removed from it"
                }

            // The reflected 4711 must not be RESTATED IN METADATA, where it sat one field away from
            // the column that says UNKNOWN. It does still appear in `after_state`, which is
            // `data.toString()` — a state snapshot of what the operation returned, not a claim about
            // which entity the row is ABOUT. That is a weaker and different argument than the one
            // buildSuccessMetadata's KDoc makes, and stripping it is a separate question: an
            // operator reading the expanded row now sees `ID: UNKNOWN` above a raw `After:` dump,
            // not two fields both labelled as the entity's id and disagreeing.
            assertFalse(metadata.contains("4711"), "metadata was: $metadata")

            // "Keep every other key" was asserted nowhere. Deleting them all also removes the id.
            assertTrue(metadata.contains("\"context\""), "metadata lost its context key: $metadata")
            assertTrue(metadata.contains("\"note\""), "metadata lost its note key: $metadata")
        }

    @Test
    @DisplayName("the lambda outranks the entry-log config, which outranks UNKNOWN")
    fun lambdaOutranksEntryLogConfig() =
        runBlocking {
            // employeeId is deliberately left null: EntryLogHelper.logOperation does
            // UUID.fromString(userId) and rethrows, so a non-UUID would fail the block, not the row.
            val config =
                EntryLogConfig<Created>(
                    logType = ChangeEntityType.PRODUCT,
                    logOperation = LogOperation.CREATE,
                    extractLogData = { EntryLogData(entityId = "9999", entityName = "from-config", message = "created") },
                )

            ExceptionConverter.catchingThrows(
                context = "test",
                operation = "CREATE_BILL",
                entityType = "BILL",
                entryLogConfig = config,
                auditEntity = { AuditEntityRef(id = it.billId.toString(), name = "from-lambda") },
                block = { Created(4711L, "RE-260917-369") },
            )

            val withBoth = lastAuditRow()
            assertEquals("4711", withBoth[TransactionAuditLogTable.entityId], "tier 1 must outrank tier 2")
            assertEquals("from-lambda", withBoth[TransactionAuditLogTable.entityName])

            ExceptionConverter.catchingThrows(
                context = "test",
                operation = "CREATE_BILL",
                entityType = "BILL",
                entryLogConfig = config,
                block = { Created(4711L, "RE-260917-369") },
            )

            // The only proof tier 2 fires at all: not one of the twelve audited call sites in the
            // repo passes an entryLogConfig today, so nothing else exercises it.
            val configOnly = lastAuditRow()
            assertEquals("9999", configOnly[TransactionAuditLogTable.entityId], "tier 2 must be reachable")
            assertEquals("from-config", configOnly[TransactionAuditLogTable.entityName])
        }

    @Test
    @DisplayName("a throwing auditEntity lambda neither fails the operation nor corrupts the row")
    fun throwingLambdaIsContained() =
        runBlocking {
            val result =
                ExceptionConverter.catchingThrows(
                    context = "test",
                    employeeId = "tester",
                    operation = "CREATE_BILL",
                    entityType = "BILL",
                    auditEntity = { error("boom") },
                    block = { Created(4711L, "RE-260917-369") },
                )

            assertEquals(4711L, result.billId, "the business operation must still succeed")
            assertEquals("UNKNOWN", lastAuditRow()[TransactionAuditLogTable.entityId])
            // A THROW is a genuine failure and must stay loud — this is the half of the contract
            // that the quiet null path below is defined against.
            assertTrue(
                warningsFromTheConverter().any { it.contains("auditEntity lambda threw") },
                "a throwing lambda must warn; it got ${warningsFromTheConverter()}",
            )
        }

    /**
     * These two legs are separate @Tests ON PURPOSE. Run in one test against one database they
     * share [lastAuditRow], which returns the newest EXISTING row: drop the init guard and the
     * over-long leg's insert overflows, the catch-all in logSuccessToAudit drops its row, and
     * lastAuditRow returns the BLANK leg's row — which also says UNKNOWN, so the leg whose whole
     * point is "an overflow would have lost the row entirely" is the one that cannot see it being
     * lost. A database per test leaves each holding exactly its own row, so .single() throws
     * loudly when the row is gone.
     */
    @Test
    @DisplayName("a blank id degrades the row to UNKNOWN instead of writing a cell that reads as data")
    fun blankIdDegradesToUnknown() =
        runBlocking {
            val result =
                ExceptionConverter.catchingThrows(
                    context = "test",
                    employeeId = "tester",
                    operation = "CREATE_BILL",
                    entityType = "BILL",
                    auditEntity = { AuditEntityRef(id = "") },
                    block = { Created(4711L, "RE-260917-369") },
                )

            assertEquals(4711L, result.billId, "a blank id must not fail the business operation")
            assertEquals("UNKNOWN", lastAuditRow()[TransactionAuditLogTable.entityId], "a blank id must not be written")
        }

    @Test
    @DisplayName("an over-long id degrades the row to UNKNOWN instead of losing it")
    fun overLongIdKeepsTheRow() =
        runBlocking {
            // entity_id is varchar(50). Without the init guard this insert overflows inside
            // logSuccessToAudit's dbQuery, the catch-all swallows it and NO row is written at all —
            // strictly worse than UNKNOWN. lastAuditRow().single() is what notices.
            val result =
                ExceptionConverter.catchingThrows(
                    context = "test",
                    employeeId = "tester",
                    operation = "CREATE_BILL",
                    entityType = "BILL",
                    auditEntity = { AuditEntityRef(id = "X".repeat(51)) },
                    block = { Created(4711L, "RE-260917-369") },
                )

            assertEquals(4711L, result.billId, "an over-long id must not fail the business operation")
            assertEquals(
                "UNKNOWN",
                lastAuditRow()[TransactionAuditLogTable.entityId],
                "the row must survive, degraded — an overflow would have lost it entirely",
            )
        }

    @Test
    @DisplayName("an entry-log config naming an unusable entity degrades the row, never the operation")
    fun unusableConfigIdIsContained() =
        runBlocking {
            // Tier 2 builds the ref itself, outside any call-site lambda, so it has no runCatching
            // of its own — that is the containment this pins. The id is BLANK rather than over-long
            // on purpose: entry_log.entity_id is varchar(50) and logOperation does not truncate it,
            // so a 51-char id would throw out of logEntryOperation (inside the ambient dbQuery,
            // deliberately un-swallowed) before resolveAuditEntity is ever reached. The length
            // branch is therefore unreachable from tier 2; blank is the one that gets here.
            val result =
                ExceptionConverter.catchingThrows(
                    context = "test",
                    operation = "CREATE_BILL",
                    entityType = "BILL",
                    entryLogConfig =
                        EntryLogConfig<Created>(
                            logType = ChangeEntityType.PRODUCT,
                            logOperation = LogOperation.CREATE,
                            extractLogData = { EntryLogData(entityId = "", entityName = "from-config") },
                        ),
                    block = { Created(4711L, "RE-260917-369") },
                )

            assertEquals(
                4711L,
                result.billId,
                "the guard must degrade the row, never fail an operation that has already committed",
            )
            assertEquals("UNKNOWN", lastAuditRow()[TransactionAuditLogTable.entityId])
        }

    @Test
    @DisplayName("a lambda names an entity nested inside the returned holder")
    fun lambdaReachesThroughANestedHolder() =
        runBlocking {
            // BillServiceImpl.createBill returns a holder whose bill id and Beleg number live on a
            // nested signing-data class; nothing at the top level identifies the bill. This pins that
            // the value handed to the lambda is the block's own return, reachable to any depth.
            val result =
                ExceptionConverter.catchingThrows(
                    context = "test",
                    employeeId = "tester",
                    operation = "CREATE_BILL",
                    entityType = "BILL",
                    auditEntity = { AuditEntityRef(id = it.signingData.billId.toString(), name = it.signingData.billNumber) },
                    block = { BillCreation(summary = "created", signingData = SigningData(4711L, "RE-260917-369")) },
                )

            assertEquals(4711L, result.signingData.billId, "the business value must still be returned")
            val row = lastAuditRow()
            assertEquals("4711", row[TransactionAuditLogTable.entityId])
            assertEquals("RE-260917-369", row[TransactionAuditLogTable.entityName], "id and name must not be swapped")
        }

    @Test
    @DisplayName("a lambda may name the call's own subject while labelling what the call produced")
    fun lambdaMayNameTheCallsSubjectRatherThanItsReturn() =
        runBlocking {
            // deleteBill returns the REVERSAL it created, never the bill it acted on. Its lambda
            // therefore takes the id from the method's own billId parameter and only the label from
            // the returned value, so a DELETE_BILL row carries the same entity_id
            // its CREATE_BILL row did and the two join into one bill's lifecycle.
            val reversedBillId = "88"

            val result =
                ExceptionConverter.catchingThrows(
                    context = "test",
                    employeeId = "tester",
                    operation = "DELETE_BILL",
                    entityType = "BILL",
                    auditEntity = { AuditEntityRef(id = reversedBillId, name = "Storno: ${it.billNumber}") },
                    block = { SigningData(billId = 9999L, billNumber = "RE-260917-370") },
                )

            assertEquals(9999L, result.billId, "the business value must still be returned")
            val row = lastAuditRow()
            assertEquals("88", row[TransactionAuditLogTable.entityId], "the id must be the bill reversed, not the reversal")
            assertEquals("Storno: RE-260917-370", row[TransactionAuditLogTable.entityName])
        }

    @Test
    @DisplayName("a transfer that moved a session names that session")
    fun transferNamesTheSessionItMoved() =
        runBlocking {
            val result =
                ExceptionConverter.catchingThrows(
                    context = "test",
                    employeeId = "tester",
                    operation = "TRANSFER_TABLE",
                    entityType = "TABLE_SESSION",
                    auditEntity = { outcome ->
                        val sessionId = outcome.sessionId ?: error("no session id")
                        AuditEntityRef(id = sessionId.toString(), name = outcome.message)
                    },
                    block = { TransferOutcome("SESSION_MOVE", 12L, "moved session 12 from table 3 to table 7") },
                )

            assertEquals(12L, result.sessionId, "the business value must still be returned")
            val row = lastAuditRow()
            assertEquals("12", row[TransactionAuditLogTable.entityId])
            assertEquals("moved session 12 from table 3 to table 7", row[TransactionAuditLogTable.entityName])
        }

    @Test
    @DisplayName("a transfer carrying no session id declines quietly, and never writes the string null")
    fun transferWithoutASessionIdStaysUnknown() =
        runBlocking {
            // The ITEM_TRANSFER branch of transferTable returns a response with sessionId == null. The
            // only non-null ids it carries are TABLE ids, which would collide numerically with an
            // unrelated real session under entityType=TABLE_SESSION, so the lambda declines by
            // returning null. Three halves, all pinned below: the row degrades to UNKNOWN; a bare
            // `.toString()` on the nullable — which would write the literal text "null", data an
            // auditor cannot tell from an id — is not what happens; and DECLINING IS SILENT. An item
            // transfer is a routine success in a busy service, so the throw this used to use wrote a
            // stack trace per transfer for something that is not an error, which is how a warning
            // stops meaning anything.
            val result =
                ExceptionConverter.catchingThrows(
                    context = "test",
                    employeeId = "tester",
                    operation = "TRANSFER_TABLE",
                    entityType = "TABLE_SESSION",
                    auditEntity = { outcome ->
                        outcome.sessionId?.let { sessionId -> AuditEntityRef(id = sessionId.toString(), name = outcome.message) }
                    },
                    block = { TransferOutcome("ITEM_TRANSFER", null, "transferred 3 items from table 3 to table 7") },
                )

            assertEquals("ITEM_TRANSFER", result.transferType, "the transfer itself must still succeed")
            val entityId = lastAuditRow()[TransactionAuditLogTable.entityId]
            assertNotEquals("null", entityId, "a bare toString on the nullable id would write this")
            assertEquals("UNKNOWN", entityId)
            assertEquals(
                emptyList(),
                warningsFromTheConverter(),
                "declining to name is not a failure and must log nothing",
            )
        }

    @Test
    @DisplayName("an over-long name is clipped to the column, and never costs the row")
    fun overLongNameKeepsTheRow() =
        runBlocking {
            // entity_name is varchar(255) and, unlike entity_id, AuditEntityRef does NOT reject an
            // over-long one: logSuccessToAudit truncates instead, because a clipped name still
            // identifies the row while an overflow would throw inside its dbQuery, be swallowed by
            // its catch-all, and lose the row entirely. The call sites now compose names
            // ("Storno: …", "Zahlartwechsel: …", "Z-Bon …") rather than copying a short column, so
            // this is the first change that can push one past the limit. lastAuditRow().single() is
            // what notices a lost row: this test gets its own database.
            val longName = "Z-Bon ".repeat(60)
            assertTrue(longName.length > 255, "the fixture must actually exceed the column")

            val result =
                ExceptionConverter.catchingThrows(
                    context = "test",
                    employeeId = "tester",
                    operation = "CREATE_REPORT",
                    entityType = "Z-report",
                    auditEntity = { AuditEntityRef(id = it.billId.toString(), name = longName) },
                    block = { Created(4711L, "RE-260917-369") },
                )

            assertEquals(4711L, result.billId, "an over-long name must not fail the business operation")
            val row = lastAuditRow()
            assertEquals("4711", row[TransactionAuditLogTable.entityId], "the id must survive intact")
            assertEquals(longName.take(255), row[TransactionAuditLogTable.entityName])
        }

    @Test
    @DisplayName("a cash-book entry is named by the header id its projection carries")
    fun cashBookEntryIsNamedByItsHeaderId() =
        runBlocking {
            val result =
                ExceptionConverter.catchingThrows(
                    context = "test",
                    employeeId = "tester",
                    operation = "CREATE_CASH_BOOK_ENTRY",
                    entityType = "KASSENBUCH",
                    auditEntity = { group ->
                        val projection = group.entries.firstOrNull()
                        AuditEntityRef(id = projection?.id?.toString() ?: group.belegNr, name = group.belegNr)
                    },
                    block = { EntryGroup(belegNr = "2026-09-0001", entries = listOf(EntryProjection(id = 77L))) },
                )

            assertEquals("2026-09-0001", result.belegNr, "the business value must still be returned")
            val row = lastAuditRow()
            assertEquals("77", row[TransactionAuditLogTable.entityId], "the header id, not the Beleg number")
            assertEquals("2026-09-0001", row[TransactionAuditLogTable.entityName])
        }

    @Test
    @DisplayName("a cash-book entry with no projection falls back to its Beleg number, not to UNKNOWN")
    fun cashBookEntryFallsBackToItsBelegNumber() =
        runBlocking {
            // The fallback names the SAME entry by its other key, so it is a usable id rather than a
            // different entity's. It also cannot be confused for a header id: a Beleg number is
            // "YYYY-MM-NNNN", never a bare integer.
            ExceptionConverter.catchingThrows(
                context = "test",
                employeeId = "tester",
                operation = "CREATE_CASH_BOOK_ENTRY",
                entityType = "KASSENBUCH",
                auditEntity = { group ->
                    val projection = group.entries.firstOrNull()
                    AuditEntityRef(id = projection?.id?.toString() ?: group.belegNr, name = group.belegNr)
                },
                block = { EntryGroup(belegNr = "2026-09-0002", entries = emptyList()) },
            )

            assertEquals("2026-09-0002", lastAuditRow()[TransactionAuditLogTable.entityId])
        }
}
