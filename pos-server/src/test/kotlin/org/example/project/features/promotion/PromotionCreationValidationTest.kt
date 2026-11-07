package org.example.project.features.promotion

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.promotion.PromotionDto
import org.example.project.core.domain.enums.promotion.DiscountType
import org.example.project.core.domain.enums.promotion.PromotionScope
import org.example.project.features.promotion.data.repository.PromotionRepositoryImpl
import org.example.project.features.promotion.data.service.PromotionServiceImpl
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
 * A promotion a manager can save but a cashier can never use is a support call waiting to happen:
 * the failure surfaces mid-checkout, to the wrong person, long after the mistake was made. These
 * cases were all reachable — each one returned 201 and only blew up (or was silently clamped) at
 * the till.
 *
 * The oversized-FIXED_AMOUNT rule deliberately does NOT replace the runtime
 * `minOf(discountValue, targetItem.finalPrice)` clamp in `BillServiceImpl`: a catalog price can be
 * lowered after the promotion is created, and `allowCustomPrice` items sell below catalog anyway,
 * so the clamp is what actually stops a negative line item from corrupting the VAT bucket, the
 * Z-Bon and the DSFinV-K export. See [PromotionClampRegressionTest].
 */
@DisplayName("Promotion validation at creation, not at the till")
class PromotionCreationValidationTest {
    private val service = PromotionServiceImpl(PromotionRepositoryImpl())

    @BeforeEach
    fun setup() = TestDatabase.setup()

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    private fun adminId(): String =
        transaction {
            TestData
                .createAdmin()
                .id.value
                .toString()
        }

    private fun productPriced(price: String): Long =
        transaction {
            val category = TestData.createCategory("Val Cat")
            TestData
                .createProduct(
                    categoryId = category.id.value,
                    productName = "Val Prod",
                    productNumber = "VAL_P1",
                    price = BigDecimal(price),
                ).id.value
        }

    @Test
    fun `BILL_WIDE FREE_ITEM is rejected at creation, not at the till`() =
        runBlocking {
            val ex =
                assertFailsWith<PosException> {
                    service.createPromotion(
                        employeeId = adminId(),
                        request =
                            PromotionDto.CreatePromotionRequestDto(
                                name = "gratis auf alles",
                                discountType = DiscountType.FREE_ITEM.name,
                                discountValue = "0.00",
                                scope = PromotionScope.BILL_WIDE.name,
                                validFrom = currentTimeStamp().toString(),
                            ),
                    )
                }
            assertEquals(HttpStatusCode.BadRequest, ex.httpStatus)
            assertTrue(
                ex.message?.contains("ITEM_SPECIFIC") == true,
                "message should point at the fix (ITEM_SPECIFIC); got: ${ex.message}",
            )
            Unit
        }

    @Test
    fun `ITEM_SPECIFIC without a target product is rejected`() =
        runBlocking {
            val ex =
                assertFailsWith<PosException> {
                    service.createPromotion(
                        employeeId = adminId(),
                        request =
                            PromotionDto.CreatePromotionRequestDto(
                                name = "ohne Ziel",
                                discountType = DiscountType.FIXED_AMOUNT.name,
                                discountValue = "1.00",
                                scope = PromotionScope.ITEM_SPECIFIC.name,
                                targetProductId = null,
                                validFrom = currentTimeStamp().toString(),
                            ),
                    )
                }
            assertEquals(HttpStatusCode.BadRequest, ex.httpStatus)
            assertTrue(
                ex.message?.contains("targetProductId") == true,
                "message should name the missing field; got: ${ex.message}",
            )
            Unit
        }

    @Test
    fun `a FIXED_AMOUNT discount above the target product price is rejected`() =
        runBlocking {
            val productId = productPriced("19.00")
            val ex =
                assertFailsWith<PosException> {
                    service.createPromotion(
                        employeeId = adminId(),
                        request =
                            PromotionDto.CreatePromotionRequestDto(
                                name = "50 EUR auf ein 19 EUR Produkt",
                                discountType = DiscountType.FIXED_AMOUNT.name,
                                discountValue = "50.00",
                                scope = PromotionScope.ITEM_SPECIFIC.name,
                                targetProductId = productId,
                                validFrom = currentTimeStamp().toString(),
                            ),
                    )
                }
            assertEquals(HttpStatusCode.BadRequest, ex.httpStatus)
            assertTrue(
                ex.message?.contains("19.00") == true && ex.message?.contains("50") == true,
                "message should contrast the discount with the product price; got: ${ex.message}",
            )
            Unit
        }

    @Test
    fun `a FIXED_AMOUNT discount equal to the target product price is accepted`() =
        runBlocking {
            val productId = productPriced("19.00")
            val dto =
                service.createPromotion(
                    employeeId = adminId(),
                    request =
                        PromotionDto.CreatePromotionRequestDto(
                            name = "Produkt gratis via Festbetrag",
                            discountType = DiscountType.FIXED_AMOUNT.name,
                            discountValue = "19.00",
                            scope = PromotionScope.ITEM_SPECIFIC.name,
                            targetProductId = productId,
                            validFrom = currentTimeStamp().toString(),
                        ),
                )
            assertEquals(DiscountType.FIXED_AMOUNT.name, dto.discountType)
            Unit
        }

    @Test
    fun `validUntil before validFrom is rejected`() =
        runBlocking {
            val ex =
                assertFailsWith<PosException> {
                    service.createPromotion(
                        employeeId = adminId(),
                        request =
                            PromotionDto.CreatePromotionRequestDto(
                                name = "endet vor dem Start",
                                discountType = DiscountType.PERCENTAGE.name,
                                discountValue = "10.00",
                                scope = PromotionScope.BILL_WIDE.name,
                                validFrom = "2026-08-20T10:00:00",
                                validUntil = "2026-08-19T10:00:00",
                            ),
                    )
                }
            assertEquals(HttpStatusCode.BadRequest, ex.httpStatus)
            assertTrue(
                ex.message?.contains("validUntil") == true,
                "message should name validUntil; got: ${ex.message}",
            )
            Unit
        }

    @Test
    fun `a BILL_WIDE promotion without a target product is still accepted`() =
        runBlocking {
            val dto =
                service.createPromotion(
                    employeeId = adminId(),
                    request =
                        PromotionDto.CreatePromotionRequestDto(
                            name = "10 EUR aufs Ganze",
                            discountType = DiscountType.FIXED_AMOUNT.name,
                            discountValue = "10.00",
                            scope = PromotionScope.BILL_WIDE.name,
                            validFrom = currentTimeStamp().toString(),
                        ),
                )
            assertEquals(PromotionScope.BILL_WIDE.name, dto.scope)
            Unit
        }
}
