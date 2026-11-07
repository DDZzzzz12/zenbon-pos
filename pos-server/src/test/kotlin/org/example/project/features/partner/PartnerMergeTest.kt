package org.example.project.features.partner

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.project.core.domain.dto.audit.TransactionAuditDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.audit.data.repository.TransactionAuditRepositoryImpl
import org.example.project.features.audit.data.service.TransactionAuditServiceImpl
import org.example.project.features.audit.database.tables.TransactionAuditLogTable
import org.example.project.features.debtor.data.repository.DebtorRepositoryImpl
import org.example.project.features.debtor.domain.models.Debtor
import org.example.project.features.partner.data.repository.BusinessPartnerRepositoryImpl
import org.example.project.features.partner.data.service.BusinessPartnerServiceImpl
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.supplier.data.repository.SupplierRepositoryImpl
import org.example.project.features.supplier.domain.models.Supplier
import org.example.project.features.supplier.domain.models.SupplierBankAccount
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.plugins.TRACE_ID_MDC_KEY
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Declaring two parties the same company.
 *
 * The merge is **soft**: the losing row stays, pointing at the survivor, so the decision is
 * reversible and every stored reference to the loser keeps resolving.
 */
@DisplayName("Partner merge")
class PartnerMergeTest {
    private val partners = BusinessPartnerRepositoryImpl()
    private val suppliers = SupplierRepositoryImpl()
    private val debtors = DebtorRepositoryImpl()

    private fun service() =
        BusinessPartnerServiceImpl(
            partners = partners,
            auditService = TransactionAuditServiceImpl(TransactionAuditRepositoryImpl(), Json),
        )

    /**
     * Hygiene, NOT load-bearing here — and I checked rather than assuming.
     *
     * Deleting this line leaves all six green even with `@Order` forcing the trace-id test to run
     * first, because each `testApp` block runs on its own coroutine and the MDC set inside one does
     * not reach the next. That differs from `AuditTraceIdTest`, which uses plain `runBlocking` on
     * the JUnit thread and where the same deletion genuinely does leak. Kept because it costs
     * nothing and the difference is a property of `testApp`, not something this class should rely on.
     */
    @AfterEach
    fun clearTraceId() {
        MDC.remove(TRACE_ID_MDC_KEY)
    }

    /** Two parties naming the same company, ready to be merged. Returns `survivorId to losingId`. */
    private fun mergeablePair(): Pair<Long, Long> =
        transaction {
            val supplier = suppliers.create(name = "Metro AG", vatId = "DE123456789")
            val debtor =
                debtors.create(
                    shop = ShopInfo.all().first(),
                    name = "Metro AG",
                    vatId = "DE123456789",
                )
            supplier.partner.id.value to debtor.partner.id.value
        }

    /** The single `MERGE_BUSINESS_PARTNER` row, or a failure if there is not exactly one. */
    private fun theMergeRow() =
        transaction {
            TransactionAuditLogTable
                .selectAll()
                .where {
                    TransactionAuditLogTable.operation eq
                        TransactionAuditDto.TransactionOperation.MERGE_BUSINESS_PARTNER.name
                }.single()
        }

    @Test
    fun `roles move to the survivor and the losing party is kept, pointing at it`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val ids =
                transaction {
                    val supplier = suppliers.create(name = "Metro AG", vatId = "DE123456789")
                    val debtor =
                        debtors.create(
                            shop = ShopInfo.all().first(),
                            name = "Metro AG",
                            vatId = "DE123456789",
                        )
                    supplier.partner.id.value to debtor.partner.id.value
                }
            val (survivorId, losingId) = ids

            service().merge(survivorId = survivorId, losingId = losingId, actingEmployeeId = "emp-1")

            transaction {
                assertEquals(
                    survivorId,
                    Supplier
                        .all()
                        .single()
                        .partner.id.value,
                )
                assertEquals(
                    survivorId,
                    Debtor
                        .all()
                        .single()
                        .partner.id.value,
                )
                assertEquals(
                    survivorId,
                    partners.findByIdFollowingMerges(losingId)?.id?.value,
                    "the losing id must still resolve, to the survivor",
                )
            }
        }
    }

    /**
     * `supplier` carries `UNIQUE (partner_id)`. Which creditor survives decides the creditor number
     * the DATEV export writes, so the merge refuses rather than picking one.
     */
    @Test
    fun `two parties that each carry a creditor role are refused`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val ids =
                transaction {
                    val first = suppliers.create(name = "Metro AG", vatId = "DE123456789")
                    val second = suppliers.create(name = "Metro AG", vatId = "DE999999999")
                    first.partner.id.value to second.partner.id.value
                }
            val (survivorId, losingId) = ids

            val failure =
                assertFailsWith<PosException> {
                    service().merge(survivorId = survivorId, losingId = losingId, actingEmployeeId = null)
                }
            assertEquals(PosErrorCode.PARTNER_MERGE_CREDITOR_CONFLICT, failure.code)

            transaction {
                assertEquals(
                    2,
                    Supplier.all().count().toInt(),
                    "a refused merge must leave both creditors standing",
                )
            }
            // JUnit5 reports a test whose body ends in assertFailsWith as skipped.
            Unit
        }
    }

    /** E2E round 4, R4-11: this reached the database's unique index and came back as a 500. */
    @Test
    fun `two parties that each carry a debtor role are refused`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val (survivorId, losingId) =
                transaction {
                    val shop = ShopInfo.all().first()
                    val first = debtors.create(shop = shop, name = "Muster Catering GmbH", vatId = null)
                    val second = debtors.create(shop = shop, name = "Muster Catering", vatId = null)
                    first.partner.id.value to second.partner.id.value
                }

            val failure =
                assertFailsWith<PosException> {
                    service().merge(survivorId = survivorId, losingId = losingId, actingEmployeeId = null)
                }
            assertEquals(PosErrorCode.PARTNER_MERGE_DEBTOR_CONFLICT, failure.code)

            transaction {
                assertEquals(2, Debtor.all().count().toInt(), "a refused merge must leave both debtors standing")
            }
            Unit
        }
    }

    /** E2E round 4, R4-9: the audit row stored the acting employee's UUID as its employee number. */
    @Test
    fun `the audit row names the acting employee by number`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val admin = TestData.createAdmin(employeeNumber = "M_R49", username = "merge_r49")
            val (survivorId, losingId) = mergeablePair()

            service().merge(survivorId = survivorId, losingId = losingId, actingEmployeeId = admin.id.value.toString())

            assertEquals("M_R49", theMergeRow()[TransactionAuditLogTable.employeeNumber])
        }
    }

    @Test
    fun `bank accounts move to the survivor and the same IBAN is not stored twice`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val ids =
                transaction {
                    val supplier = suppliers.create(name = "Metro AG", vatId = "DE123456789")
                    suppliers.registerBankAccount(
                        supplier,
                        "DE21500500009876543210",
                        bic = null,
                        invoiceId = null,
                    )
                    val debtor =
                        debtors.create(
                            shop = ShopInfo.all().first(),
                            name = "Metro AG",
                            vatId = "DE123456789",
                        )
                    // The same account, entered independently on the customer side.
                    SupplierBankAccount.new {
                        partner = debtor.partner
                        iban = "DE21500500009876543210"
                    }
                    SupplierBankAccount.new {
                        partner = debtor.partner
                        iban = "DE02120300000000202051"
                    }
                    supplier.partner.id.value to debtor.partner.id.value
                }
            val (survivorId, losingId) = ids

            service().merge(survivorId = survivorId, losingId = losingId, actingEmployeeId = "emp-1")

            transaction {
                val survivor = partners.findByIdFollowingMerges(survivorId)!!
                val ibans = survivor.bankAccounts.map { it.iban }
                assertEquals(
                    listOf("DE02120300000000202051", "DE21500500009876543210"),
                    ibans.sorted(),
                    "the shared IBAN is one account, the other one moved",
                )
            }
        }
    }

    @Test
    fun `a merge is written to the transaction audit log`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val (survivorId, losingId) = mergeablePair()

            service().merge(survivorId = survivorId, losingId = losingId, actingEmployeeId = "emp-1")

            val row = theMergeRow()
            assertEquals("business_partner", row[TransactionAuditLogTable.entityType])
            assertEquals(survivorId.toString(), row[TransactionAuditLogTable.entityId])
            assertEquals("emp-1", row[TransactionAuditLogTable.employeeId])
            val beforeState =
                requireNotNull(row[TransactionAuditLogTable.beforeState]) { "the merge must record what it merged away" }
            assertTrue(
                beforeState.contains(losingId.toString()),
                "the entry must name the party that was merged away",
            )

            // `contains(losingId)` alone passes identically for JSON and for Kotlin map text, which
            // is how `serializeToJson`'s `Map<*, *>` branch went its whole life throwing on the star
            // projection and silently falling back to `obj.toString()` — so this column had NEVER
            // held JSON. Parse it: nothing weaker can tell `{"losingId":"42"}` from `{losingId=42}`.
            val parsed = Json.parseToJsonElement(beforeState).jsonObject
            assertEquals(losingId.toString(), parsed.getValue("losingId").jsonPrimitive.content)
        }
    }

    /**
     * A merge is request-backed — its only caller is `PartnerRoutes`' POST /merge — so it was the
     * last audit writer reached from a route that still recorded a null trace id.
     *
     * This is the same class of row as the TSE queue's: master data with fiscal reach, which is why
     * `recordMerge`'s own KDoc says every merge is recorded. Asserted over the COLUMN rather than a
     * fake, because the id crosses `merge` → `recordMerge` → `logTransaction` → `repository.create`
     * → `trace_id`, and a break anywhere in that chain leaves a null column while every intermediate
     * signature still compiles.
     */
    @Test
    fun `a merge under a request records that request's trace id`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val (survivorId, losingId) = mergeablePair()

            MDC.put(TRACE_ID_MDC_KEY, MERGE_TRACE)
            service().merge(survivorId = survivorId, losingId = losingId, actingEmployeeId = "emp-1")

            val row = theMergeRow()
            // Gate first: the trace assertion is vacuous unless this is the merge row.
            assertEquals(survivorId.toString(), row[TransactionAuditLogTable.entityId])
            assertEquals(MERGE_TRACE, row[TransactionAuditLogTable.traceId])
            assertTrue(
                row[TransactionAuditLogTable.beforeState]?.contains(losingId.toString()) == true,
            )
        }
    }

    /** The complement: nothing is fabricated when no request stands behind the merge. */
    @Test
    fun `a merge with no request behind it records no trace id`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val (survivorId, losingId) = mergeablePair()

            service().merge(survivorId = survivorId, losingId = losingId, actingEmployeeId = "emp-1")

            val row = theMergeRow()
            assertEquals(survivorId.toString(), row[TransactionAuditLogTable.entityId])
            assertNull(row[TransactionAuditLogTable.traceId])
        }
    }

    private companion object {
        const val MERGE_TRACE = "b4f7d2a9-16c3-4e58-8a71-0d9e3c6f2b48"
    }
}
