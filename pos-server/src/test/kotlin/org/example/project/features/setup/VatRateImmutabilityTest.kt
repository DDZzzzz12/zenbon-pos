package org.example.project.features.setup

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.setup.VatDto
import org.example.project.features.order.domain.models.Orders
import org.example.project.features.order.domain.service.OrderService
import org.example.project.features.payment.domain.models.Vat
import org.example.project.features.setup.data.repository.VatRepositoryImpl
import org.example.project.features.setup.data.service.VatRateGuard
import org.example.project.features.setup.data.service.VatServiceImpl
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Round-4 KABS-3: `updateVatRate` mutated an in-use `Vat` row in place with no reference check
 * (unlike `deleteVatRate`, which has `canDelete`) and no historisation.
 *
 * `OrderItem.vat` is a **live FK**, so once turnover references a rate, changing that rate
 * retroactively re-nets every already-closed Geschäftsvorfall pointing at it, while
 * `transactions_vat` keeps the **frozen** amounts written at closing time. The same Kassenabschluss
 * then exports two different VAT decompositions for one and the same key, and a delivered closing
 * changes its figures on re-export.
 *
 * DSFinV-K Tz 3.2.6 models this the other way round: the rate booked is the one that applied
 * „im Zeitpunkt der Erfassung des Geschäftsvorfalls"; a new statutory rate gets a NEW ID (historical
 * IDs from 11) instead of overwriting the current one. The guard therefore rejects a *rate value*
 * change on a referenced row while still allowing a `vatClass` rename, forcing the operator down the
 * historisation path that already exists (create a new `Vat` row, repoint `VatMappingConfig`, which
 * resolves by primary key — `ProductTable` carries no vat FK, so no product needs touching).
 *
 * **Fixture reachability:** the "referenced" state is produced by the *real* `OrderService.createOrder`,
 * and the referenced `vatId` is read back off the persisted `OrderItem` rather than assumed — so the
 * test neither fabricates an unreachable row nor depends on how `VatMappingUtil` resolves the
 * product's VAT class.
 *
 * **Why everything lives in `setupData`:** historical, and no longer required. Ktor's
 * `testApplication` starts the application module lazily, so with no HTTP call in the trailing block
 * `setupData` (which performs `TestDatabase.setup()` and starts Koin) used to run *after* it, leaving
 * assertions in the block to execute against an unconnected database. `testApp` now calls
 * `startApplication()` before the block, so these assertions could move there normally; this test
 * simply predates that change. Same shape as `OrderFinalPriceScaleTest`.
 */
@DisplayName("VAT rates referenced by turnover are rate-immutable (KABS-3, Tz 3.2.6)")
class VatRateImmutabilityTest {
    private val vatService = VatServiceImpl(VatRepositoryImpl(), VatRateGuard)

    private data class Fixture(
        val adminId: String,
        val referencedVatId: String,
    )

    /**
     * Sells one item through the real order pipeline and returns the `Vat` id the persisted
     * `OrderItem` actually references — a genuinely reachable "this rate carries turnover" state.
     */
    private fun seedSaleAndCaptureReferencedVat(suffix: String): Fixture {
        val adminId =
            TestData
                .createAdmin(
                    employeeNumber = "VRI_ADM$suffix",
                    username = "vri_adm$suffix",
                ).id.value
                .toString()
        val category = TestData.createCategory(name = "VRI_Cat$suffix")
        val product =
            TestData.createProduct(
                categoryId = category.id.value,
                productName = "VRI_Prod$suffix",
                productNumber = "VRI_P$suffix",
                price = BigDecimal("10.0000"),
            )
        TestData.createDefaultVatRates()
        val tws = TestData.createTableWithSession(tableNumber = "VRI_TBL$suffix")
        val deviceId = "vri-device-$suffix"
        transaction {
            Terminal.new {
                this.deviceId = deviceId
                this.clientId = "VRI-CLIENT-$suffix"
                this.isActive = true
                this.registeredAt = currentTimeStamp()
                this.lastSeenAt = currentTimeStamp()
            }
        }

        val orderService = GlobalContext.get().get<OrderService>()
        val response =
            runBlocking {
                orderService.createOrder(
                    request =
                        OrderDto.CreateOrderRequestDto(
                            tableInfoId = tws.session.id.value,
                            tableId = tws.table.id.value,
                            orderItems =
                                listOf(
                                    OrderItemDto.CreateOrderItemRequestDto(
                                        productId = product.id.value,
                                        unitPrice = "10.0",
                                        quantity = 1,
                                        discount = 1.0,
                                    ),
                                ),
                        ),
                    employeeId = adminId,
                    deviceId = deviceId,
                    actorCapabilities = emptySet(),
                )
            }

        val referencedVatId =
            transaction {
                Orders
                    .findById(response.orderId)!!
                    .items
                    .single()
                    .vat.id.value
                    .toString()
            }
        return Fixture(adminId, referencedVatId)
    }

    @Test
    fun `changing the rate of a referenced Vat row is rejected`() =
        testApp(
            setupData = {
                val fixture = seedSaleAndCaptureReferencedVat("A")
                val originalRate = transaction { Vat.findById(fixture.referencedVatId)!!.vatRate }
                // 0.16 is a DSFinV-K-mappable rate (Anlage 2 key 21, the 2020 Corona rate), so this
                // cannot be mistaken for the STAM-4 mappability rejection.
                assertTrue(
                    originalRate.compareTo(BigDecimal("0.16")) != 0,
                    "the fixture rate must differ from the target rate or the test is vacuous",
                )

                val ex =
                    assertFailsWith<PosException> {
                        runBlocking {
                            vatService.updateVatRate(
                                fixture.adminId,
                                fixture.referencedVatId,
                                VatDto.UpdateVatRequestDto(vatRate = 0.16),
                            )
                        }
                    }
                assertEquals(
                    HttpStatusCode.Conflict,
                    ex.httpStatus,
                    "a rate change on a row carrying turnover is a conflict, not a validation error",
                )
                assertTrue(
                    ex.message?.contains("referenced") == true,
                    "message must say the rate is referenced by recorded turnover; got: ${ex.message}",
                )
                // …and the row must be untouched: a rejection that still wrote would be worse than none.
                assertEquals(
                    0,
                    originalRate.compareTo(transaction { Vat.findById(fixture.referencedVatId)!!.vatRate }),
                    "the rejected update must not have mutated the stored rate",
                )
                Unit // JUnit5: without this the expression body returns non-Unit and the test is SILENTLY SKIPPED
            },
        ) { _ -> }

    @Test
    fun `renaming the vatClass of a referenced row is still allowed`() =
        testApp(
            setupData = {
                val fixture = seedSaleAndCaptureReferencedVat("B")
                // A rename carries no fiscal meaning — Tz 3.2.6 freezes the RATE, not the label — so the
                // guard must stay narrow enough that operators can still correct master-data wording.
                runBlocking {
                    vatService.updateVatRate(
                        fixture.adminId,
                        fixture.referencedVatId,
                        VatDto.UpdateVatRequestDto(vatClass = "STANDARD_DE"),
                    )
                }
                assertEquals("STANDARD_DE", transaction { Vat.findById(fixture.referencedVatId)!!.vatClass })
            },
        ) { _ -> }

    @Test
    fun `re-submitting the SAME rate on a referenced row is a no-op, not a rejection`() =
        testApp(
            setupData = {
                val fixture = seedSaleAndCaptureReferencedVat("C")
                // The guard triggers on a rate CHANGE, not on the field's mere presence: a client that
                // PUTs the whole entity back unchanged must not start failing.
                val currentRate = transaction { Vat.findById(fixture.referencedVatId)!!.vatRate }
                val updated =
                    runBlocking {
                        vatService.updateVatRate(
                            fixture.adminId,
                            fixture.referencedVatId,
                            VatDto.UpdateVatRequestDto(vatClass = "UNCHANGED", vatRate = currentRate.toDouble()),
                        )
                    }
                assertEquals("UNCHANGED", updated.vatClass)
                assertEquals(
                    0,
                    currentRate.compareTo(transaction { Vat.findById(fixture.referencedVatId)!!.vatRate }),
                    "an unchanged rate must survive verbatim",
                )
            },
        ) { _ -> }

    @Test
    fun `changing the rate of an unreferenced row is allowed`() =
        testApp(
            setupData = {
                val adminId =
                    TestData
                        .createAdmin(
                            employeeNumber = "VRI_ADMD",
                            username = "vri_admd",
                        ).id.value
                        .toString()
                runBlocking {
                    vatService.createVatRate(
                        adminId,
                        VatDto.CreateVatRequestDto(vatId = "VRI_FREE", vatClass = "FREEROW", vatRate = 0.19),
                    )
                    vatService.updateVatRate(adminId, "VRI_FREE", VatDto.UpdateVatRequestDto(vatRate = 0.16))
                }
                assertEquals(
                    0,
                    BigDecimal("0.16").compareTo(transaction { Vat.findById("VRI_FREE")!!.vatRate }),
                    "a rate no turnover points at is still freely correctable",
                )
            },
        ) { _ -> }
}
