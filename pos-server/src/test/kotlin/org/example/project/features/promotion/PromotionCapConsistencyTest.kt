package org.example.project.features.promotion

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.promotion.PromotionDto
import org.example.project.core.domain.enums.promotion.DiscountType
import org.example.project.core.domain.enums.promotion.PromotionScope
import org.example.project.features.bill.data.service.scaleDeductionsToCap
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.promotion.data.repository.PromotionRepositoryImpl
import org.example.project.features.promotion.data.service.PromotionServiceImpl
import org.example.project.features.promotion.domain.models.BillPromotion
import org.example.project.features.promotion.domain.models.Promotion
import org.example.project.features.table.domain.models.TableSession
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Round-3 INV-F3: percentage promotions must be bounded 0..100 at the source, AND when the
 * legacy cap engages in [org.example.project.features.bill.data.service.BillServiceImpl] the
 * per-row [BillPromotion.deductedAmount] audit values must be scaled so their Σ equals the
 * capped `bill.promotionAmount`. Otherwise the DSFinV-K export's Rabatt lines (derived from
 * deductedAmount) disagree with the header UMS_BRUTTO (derived from the capped promotionAmount).
 */
@DisplayName("Promotion cap consistency (INV-F3)")
class PromotionCapConsistencyTest {
    private val service = PromotionServiceImpl(PromotionRepositoryImpl())

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    @Test
    fun `creating a percentage promotion above 100 percent is rejected as 422`() =
        runBlocking {
            val adminId =
                transaction {
                    TestData
                        .createAdmin()
                        .id.value
                        .toString()
                }
            val now = currentTimeStamp().toString()

            val ex =
                assertFailsWith<PosException> {
                    service.createPromotion(
                        employeeId = adminId,
                        request =
                            PromotionDto.CreatePromotionRequestDto(
                                name = "150 percent off",
                                discountType = DiscountType.PERCENTAGE.name,
                                discountValue = "150.0",
                                scope = PromotionScope.BILL_WIDE.name,
                                validFrom = now,
                            ),
                    )
                }
            assertEquals(HttpStatusCode.UnprocessableEntity, ex.httpStatus)
            assertTrue(
                ex.message?.contains("0") == true && ex.message?.contains("100") == true,
                "message should mention the 0-100 percent range; got: ${ex.message}",
            )
            Unit
        }

    @Test
    fun `creating a percentage promotion within 0 to 100 succeeds`() =
        runBlocking {
            val adminId =
                transaction {
                    TestData
                        .createAdmin()
                        .id.value
                        .toString()
                }
            val now = currentTimeStamp().toString()

            val dto =
                service.createPromotion(
                    employeeId = adminId,
                    request =
                        PromotionDto.CreatePromotionRequestDto(
                            name = "50 percent off",
                            discountType = DiscountType.PERCENTAGE.name,
                            discountValue = "50.0",
                            scope = PromotionScope.BILL_WIDE.name,
                            validFrom = now,
                        ),
                )
            assertEquals("50 percent off", dto.name)
            assertEquals(DiscountType.PERCENTAGE.name, dto.discountType)
        }

    @Test
    fun `negative percentage promotion is rejected as 422`() =
        runBlocking {
            val adminId =
                transaction {
                    TestData
                        .createAdmin()
                        .id.value
                        .toString()
                }
            val now = currentTimeStamp().toString()

            val ex =
                assertFailsWith<PosException> {
                    service.createPromotion(
                        employeeId = adminId,
                        request =
                            PromotionDto.CreatePromotionRequestDto(
                                name = "negative off",
                                discountType = DiscountType.PERCENTAGE.name,
                                discountValue = "-5",
                                scope = PromotionScope.BILL_WIDE.name,
                                validFrom = now,
                            ),
                    )
                }
            assertEquals(HttpStatusCode.UnprocessableEntity, ex.httpStatus)
            Unit
        }

    @Test
    fun `scaling caps the BillPromotion rows so their sum equals the capped total`() =
        transaction {
            val admin = TestData.createAdmin()
            val tws = TestData.createTableWithSession("CAP1")
            val session = TableSession.findById(tws.session.id)!!
            val now = currentTimeStamp()

            // Legacy data: a bill whose BillPromotion rows deduct MORE than the bill amount.
            // billAmount 100.00; two rows deducting 70.00 + 60.00 = 130.00 (cap engages).
            val bill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "CAP-1001"
                    this.billAmount = BigDecimal("100.00")
                    this.promotionAmount = BigDecimal.ZERO
                    this.paidAmount = BigDecimal.ZERO
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                }

            val promoA =
                Promotion.new {
                    this.name = "Promo A"
                    this.description = null
                    this.discountType = DiscountType.FIXED_AMOUNT
                    this.discountValue = BigDecimal("70.00")
                    this.scope = PromotionScope.BILL_WIDE
                    this.targetProduct = null
                    this.isMultiUse = false
                    this.validFrom = now
                    this.validUntil = null
                    this.isActive = true
                    this.createdBy = admin
                    this.createdAt = now
                }
            val promoB =
                Promotion.new {
                    this.name = "Promo B"
                    this.description = null
                    this.discountType = DiscountType.FIXED_AMOUNT
                    this.discountValue = BigDecimal("60.00")
                    this.scope = PromotionScope.BILL_WIDE
                    this.targetProduct = null
                    this.isMultiUse = false
                    this.validFrom = now
                    this.validUntil = null
                    this.isActive = true
                    this.createdBy = admin
                    this.createdAt = now
                }

            val rowA =
                BillPromotion.new {
                    this.bill = bill
                    this.promotion = promoA
                    this.orderItem = null
                    this.deductedAmount = BigDecimal("70.00")
                    this.appliedBy = admin
                    this.appliedAt = now
                }
            val rowB =
                BillPromotion.new {
                    this.bill = bill
                    this.promotion = promoB
                    this.orderItem = null
                    this.deductedAmount = BigDecimal("60.00")
                    this.appliedBy = admin
                    this.appliedAt = now
                }

            val uncappedTotal = rowA.deductedAmount + rowB.deductedAmount // 130.00
            val cap = bill.billAmount // 100.00

            scaleDeductionsToCap(listOf(rowA, rowB), cap, uncappedTotal)

            val sum = rowA.deductedAmount + rowB.deductedAmount
            assertEquals(
                0,
                sum.compareTo(cap),
                "Σ scaled deductedAmount must equal the capped total; got $sum vs $cap " +
                    "(rowA=${rowA.deductedAmount}, rowB=${rowB.deductedAmount})",
            )
            // Both rows scaled at 2 decimals; last row absorbs the rounding remainder.
            assertEquals(2, rowA.deductedAmount.scale())
            assertEquals(2, rowB.deductedAmount.scale())
        }
}
