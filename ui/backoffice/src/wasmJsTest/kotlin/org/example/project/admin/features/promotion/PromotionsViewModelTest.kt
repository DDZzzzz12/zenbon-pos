package org.example.project.admin.features.promotion

import kotlinx.coroutines.test.runTest
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.product.FakeProductApiClient
import org.example.project.admin.features.promotion.presentation.PromotionEditor
import org.example.project.admin.features.promotion.presentation.PromotionField
import org.example.project.admin.features.promotion.presentation.PromotionsState
import org.example.project.admin.features.promotion.presentation.PromotionsViewModel
import org.example.project.admin.features.promotion.presentation.withEditedErrorsCleared
import org.example.project.core.domain.dto.product.ProductDto
import org.example.project.core.domain.dto.promotion.PromotionDto
import org.example.project.core.network.error.NetworkApiException
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.keys.BackOfficeErrorKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun promo(
    id: Long,
    name: String,
    isActive: Boolean = true,
    scope: String = "BILL_WIDE",
    targetProductId: Long? = null,
) = PromotionDto.PromotionResponseDto(
    id = id,
    name = name,
    description = null,
    discountType = "FIXED_AMOUNT",
    discountValue = "2.50",
    scope = scope,
    targetProductId = targetProductId,
    targetProductName = null,
    isMultiUse = false,
    validFrom = "2026-08-01T00:00:00",
    validUntil = null,
    isActive = isActive,
    createdBy = "tester",
    createdAt = "2026-08-01T00:00:00",
)

private fun product(
    id: Long,
    name: String,
) = ProductDto.ProductResponseDto(
    id = id,
    productNumber = "P$id",
    name = name,
    description = null,
    price = "9.90",
    categoryId = 1,
    categoryName = "cat",
    productType = "FOOD",
    isActive = true,
)

private fun vm(
    promotionApi: FakePromotionApiClient = FakePromotionApiClient(),
    productApi: FakeProductApiClient = FakeProductApiClient(),
) = PromotionsViewModel(promotionApi, productApi)

class PromotionsViewModelTest {
    @Test
    fun refresh_populatesPromotionsAndProducts() =
        runTest {
            val model = vm(FakePromotionApiClient(listOf(promo(1, "Happy hour"))), FakeProductApiClient(listOf(product(4, "Cola"))))
            model.refresh()
            val data = assertIs<PromotionsState.Data>(model.state)
            assertEquals(listOf("Happy hour"), data.promotions.map { it.name })
            assertEquals(listOf("Cola"), data.products.map { it.name })
        }

    @Test
    fun refreshFailure_yieldsNetworkError() =
        runTest {
            val model = vm(FakePromotionApiClient(onError = { throw NetworkApiException(RuntimeException("down")) }))
            model.refresh()
            assertIs<UiError.Network>(assertIs<PromotionsState.Error>(model.state).error)
        }

    @Test
    fun create_sendsWidenedDates_andReloads() =
        runTest {
            val fake = FakePromotionApiClient()
            val model = vm(fake)
            model.refresh()
            model.openCreate()
            model.onField {
                it.copy(name = "Lunch deal", discountValue = "3.00", validFromDate = "2026-09-01", validUntilDate = "2026-09-30")
            }
            model.save()
            val created = assertNotNull(fake.created)
            assertEquals("Lunch deal", created.name)
            assertEquals("2026-09-01T00:00:00", created.validFrom)
            assertEquals("2026-09-30T23:59:59", created.validUntil)
            assertNull(model.editor)
        }

    @Test
    fun create_blankName_setsFieldError_andDoesNotCallApi() =
        runTest {
            val fake = FakePromotionApiClient()
            val model = vm(fake)
            model.refresh()
            model.openCreate()
            model.onField { it.copy(name = "", discountValue = "3.00", validFromDate = "2026-09-01") }
            model.save()
            assertNull(fake.created)
            assertEquals(AdminStringKeys.ProductFieldRequired, model.editor?.fieldErrors?.get(PromotionField.NAME))
        }

    @Test
    fun create_badDate_setsDateFieldError_andDoesNotCallApi() =
        runTest {
            val fake = FakePromotionApiClient()
            val model = vm(fake)
            model.refresh()
            model.openCreate()
            model.onField { it.copy(name = "Lunch", discountValue = "3.00", validFromDate = "01.09.2026") }
            model.save()
            assertNull(fake.created)
            assertEquals(AdminStringKeys.PromotionErrorInvalidDate, model.editor?.fieldErrors?.get(PromotionField.VALID_FROM))
        }

    // admin20 NOTE-E: an end before the start reached the server and came back as raw English.
    @Test
    fun create_endBeforeStart_setsDateOrderError_andDoesNotCallApi() =
        runTest {
            val fake = FakePromotionApiClient()
            val model = vm(fake)
            model.refresh()
            model.openCreate()
            model.onField { it.copy(name = "Lunch", discountValue = "3.00", validFromDate = "2026-10-31", validUntilDate = "2026-10-01") }
            model.save()
            assertNull(fake.created)
            assertEquals(AdminStringKeys.PromotionErrorDateOrder, model.editor?.fieldErrors?.get(PromotionField.VALID_UNTIL))

            model.onField { it.copy(validUntilDate = "2026-10-31") }
            model.save()
            assertEquals("2026-10-31T23:59:59", fake.created?.validUntil, "a one-day promotion is valid")
        }

    @Test
    fun create_itemSpecificWithoutProduct_setsProductFieldError() =
        runTest {
            val fake = FakePromotionApiClient()
            val model = vm(fake)
            model.refresh()
            model.openCreate()
            model.onField {
                it.copy(name = "Free cola", discountValue = "1.00", scope = "ITEM_SPECIFIC", validFromDate = "2026-09-01")
            }
            model.save()
            assertNull(fake.created)
            assertEquals(AdminStringKeys.PromotionErrorProductRequired, model.editor?.fieldErrors?.get(PromotionField.TARGET_PRODUCT))
        }

    @Test
    fun openEdit_prefillsDatePartOnly() =
        runTest {
            val model = vm(FakePromotionApiClient(listOf(promo(3, "Happy hour"))))
            model.refresh()
            model.openEdit(promo(3, "Happy hour"))
            assertEquals("2026-08-01", model.editor?.validFromDate)
            assertEquals("", model.editor?.validUntilDate)
        }

    @Test
    fun update_sendsOnlyEditableFields() =
        runTest {
            val fake = FakePromotionApiClient(listOf(promo(3, "Happy hour")))
            val model = vm(fake)
            model.refresh()
            model.openEdit(promo(3, "Happy hour"))
            model.onField { it.copy(name = "Happier hour", isMultiUse = true) }
            model.save()
            assertEquals(3L, fake.updatedId)
            val sent = assertNotNull(fake.updated)
            assertEquals("Happier hour", sent.name)
            assertEquals(true, sent.isMultiUse)
            assertEquals("2026-08-01T00:00:00", sent.validFrom)
        }

    /** `combinable` must survive both editor legs; it is a different axis from `isMultiUse`. */
    @Test
    fun combinable_isThreadedThroughCreateAndUpdate() =
        runTest {
            val fake = FakePromotionApiClient(listOf(promo(3, "Happy hour")))
            val model = vm(fake)
            model.refresh()

            model.openCreate()
            model.onField {
                it.copy(name = "Solo deal", discountValue = "1.00", validFromDate = "2026-09-01", combinable = false)
            }
            model.save()
            assertEquals(false, assertNotNull(fake.created).combinable)

            model.openEdit(promo(3, "Happy hour"))
            assertEquals(true, model.editor?.combinable)
            model.onField { it.copy(combinable = false) }
            model.save()
            assertEquals(false, assertNotNull(fake.updated).combinable)
        }

    @Test
    fun deactivate_callsApi() =
        runTest {
            val fake = FakePromotionApiClient(listOf(promo(9, "Happy hour")))
            val model = vm(fake)
            model.refresh()
            model.deactivate(9L)
            assertEquals(9L, fake.deactivatedId)
        }

    @Test
    fun deactivateFailure_setsActionError_andKeepsTheList() =
        runTest {
            val fake =
                FakePromotionApiClient(
                    listOf(promo(9, "Happy hour")),
                    onDeactivateError = { throw NetworkApiException(RuntimeException("down")) },
                )
            val model = vm(fake)
            model.refresh()
            model.deactivate(9L)
            val data = assertIs<PromotionsState.Data>(model.state)
            assertIs<UiError.Network>(assertNotNull(data.actionError))
            assertEquals(1, data.promotions.size)
            model.clearActionError()
            assertNull(assertIs<PromotionsState.Data>(model.state).actionError)
        }

    /**
     * Regression: `runCatchingApi`'s `onSuccess` runs outside its try block, so the reload that
     * follows a successful deactivate must carry its own error handling — otherwise it throws
     * into `viewModelScope` and the user sees a stale list with no error at all.
     */
    @Test
    fun deactivateSucceeds_butReloadFails_setsActionError() =
        runTest {
            val fake = FakePromotionApiClient(listOf(promo(9, "Happy hour")))
            val model = vm(fake)
            model.refresh()
            fake.onError = { throw NetworkApiException(RuntimeException("down")) }
            model.deactivate(9L)
            assertEquals(9L, fake.deactivatedId)
            val data = assertIs<PromotionsState.Data>(model.state)
            assertIs<UiError.Network>(assertNotNull(data.actionError))
        }

    @Test
    fun select_loadsUsage_andDeselectClearsIt() =
        runTest {
            val fake =
                FakePromotionApiClient(
                    listOf(promo(5, "Happy hour")),
                    usage = PromotionDto.PromotionUsageDto(promotionId = 5L, timesUsed = 12L, totalDiscountGiven = "30.00"),
                )
            val model = vm(fake)
            model.refresh()
            model.select(5L)
            val data = assertIs<PromotionsState.Data>(model.state)
            assertEquals(5L, data.selectedId)
            assertEquals(12L, assertNotNull(data.usage).timesUsed)
            model.select(null)
            val cleared = assertIs<PromotionsState.Data>(model.state)
            assertNull(cleared.selectedId)
            assertNull(cleared.usage)
        }

    @Test
    fun editorLifecycle_openAndClose() =
        runTest {
            val model = vm()
            model.refresh()
            model.openCreate()
            assertTrue(model.editor?.isCreate == true)
            model.closeEditor()
            assertNull(model.editor)
        }

    // ----- admin20 MD-09: „2,50“ came back as „2.50“ / reached the server raw -----

    @Test
    fun openEdit_seedsDiscountWithoutDbScaleZeros() =
        runTest {
            val model = vm(FakePromotionApiClient(listOf(promo(3, "Happy hour"))))
            model.refresh()
            model.openEdit(promo(3, "Happy hour").copy(discountValue = "2.5000"))
            // The field's locale transformation then shows „2,50“.
            assertEquals("2.50", model.editor?.discountValue)
        }

    @Test
    fun create_germanDecimal_isSentCanonical() =
        runTest {
            val fake = FakePromotionApiClient()
            val model = vm(fake)
            model.refresh()
            model.openCreate()
            model.onField { it.copy(name = "Lunch", discountValue = "2,50", validFromDate = "2026-09-01") }
            model.save()
            assertEquals("2.50", assertNotNull(fake.created).discountValue)
        }

    @Test
    fun create_unparseableDiscount_setsPriceError_andDoesNotCallApi() =
        runTest {
            val fake = FakePromotionApiClient()
            val model = vm(fake)
            model.refresh()
            model.openCreate()
            model.onField { it.copy(name = "Lunch", discountValue = "2,5x", validFromDate = "2026-09-01") }
            model.save()
            assertNull(fake.created)
            assertEquals(BackOfficeErrorKeys.priceInvalid, model.editor?.fieldErrors?.get(PromotionField.DISCOUNT_VALUE))
        }

    @Test
    fun withEditedErrorsCleared_validFromClearsValidUntil_andDiscountTypeClearsDiscountValue() {
        val before =
            PromotionEditor(
                fieldErrors =
                    mapOf(
                        PromotionField.VALID_FROM to AdminStringKeys.PromotionErrorInvalidDate,
                        PromotionField.VALID_UNTIL to AdminStringKeys.PromotionErrorDateOrder,
                        PromotionField.DISCOUNT_VALUE to BackOfficeErrorKeys.priceInvalid,
                        PromotionField.NAME to AdminStringKeys.ProductFieldRequired,
                    ),
            )
        val fromEdited = before.copy(validFromDate = "2026-09-01").withEditedErrorsCleared(before)
        assertEquals(setOf(PromotionField.DISCOUNT_VALUE, PromotionField.NAME), fromEdited.fieldErrors.keys)
        val typeEdited = before.copy(discountType = "PERCENTAGE").withEditedErrorsCleared(before)
        assertEquals(
            setOf(PromotionField.VALID_FROM, PromotionField.VALID_UNTIL, PromotionField.NAME),
            typeEdited.fieldErrors.keys,
        )
    }

    @Test
    fun create_validationAttempt_incrementsOnEachRefusedSave() =
        runTest {
            val model = vm()
            model.refresh()
            model.openCreate()
            model.save()
            assertEquals(1, model.editor?.validationAttempt)
            model.save()
            assertEquals(2, model.editor?.validationAttempt)
        }
}
