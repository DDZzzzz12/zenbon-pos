package org.example.project.admin.features.settings

import kotlinx.coroutines.test.runTest
import org.example.project.admin.features.settings.presentation.sections.VatMappingViewModel
import org.example.project.core.domain.enums.product.ProductType
import org.example.project.core.domain.models.setup.VatMappingConfig
import org.example.project.core.domain.models.setup.VatRules
import org.example.project.core.network.error.NetworkApiException
import org.example.project.core.presentation.error.UiError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VatMappingViewModelTest {
    private fun vm(
        config: FakeShopConfigApiClient = FakeShopConfigApiClient(),
        vat: FakeVatApiClient = FakeVatApiClient(),
    ) = VatMappingViewModel(config, vat)

    @Test
    fun refresh_loadsMappingAndRates() =
        runTest {
            val model = vm()
            model.refresh()
            assertEquals(VatMappingConfig.getDefault(), model.state.mapping)
            assertEquals(listOf("A", "B", "C"), model.state.vatRates.map { it.vatId })
            assertEquals(false, model.state.isLoading)
            assertNull(model.state.error)
        }

    @Test
    fun refreshFailure_yieldsNetworkError() =
        runTest {
            val model = vm(FakeShopConfigApiClient(onCall = { throw NetworkApiException(RuntimeException("down")) }))
            model.refresh()
            assertIs<UiError.Network>(model.state.error)
            assertEquals(false, model.state.isLoading)
        }

    @Test
    fun startEdit_seedsDraftFromLoadedMapping() =
        runTest {
            val model = vm()
            model.refresh()
            model.startEdit()
            assertEquals(model.state.mapping, model.state.draft)
        }

    @Test
    fun startEdit_withoutLoadedMapping_seedsTheGermanDefault() =
        runTest {
            // The server always answers with a mapping, but a section entered mid-failure must still
            // offer a usable editor rather than a dialog with no rows.
            val model = vm()
            model.startEdit()
            assertEquals(VatMappingConfig.getDefault(), model.state.draft)
        }

    @Test
    fun updateRules_replacesOnlyTheNamedProductType() =
        runTest {
            val model = vm()
            model.refresh()
            model.startEdit()
            model.updateRules(ProductType.DRINKS, VatRules(default = "A", toGo = "B"))
            val draft = assertNotNull(model.state.draft)
            assertEquals(VatRules(default = "A", toGo = "B"), draft.DRINKS)
            assertEquals(VatMappingConfig.getDefault().FOOD, draft.FOOD)
        }

    @Test
    fun cancelEdit_dropsTheDraft() =
        runTest {
            val model = vm()
            model.refresh()
            model.startEdit()
            model.updateRules(ProductType.FOOD, VatRules(default = "C"))
            model.cancelEdit()
            assertNull(model.state.draft)
            assertEquals(VatMappingConfig.getDefault(), model.state.mapping)
        }

    @Test
    fun save_sendsTheDraftAndClosesTheEditor() =
        runTest {
            val config = FakeShopConfigApiClient()
            val model = vm(config)
            model.refresh()
            model.startEdit()
            model.updateRules(ProductType.RETAIL, VatRules(default = "B", toGo = "B"))
            model.save()
            assertEquals(1, config.vatMappingRequests.size)
            assertEquals(
                VatRules(default = "B", toGo = "B"),
                config.vatMappingRequests
                    .single()
                    .vatMapping
                    .RETAIL,
            )
            assertEquals(VatRules(default = "B", toGo = "B"), model.state.mapping?.RETAIL)
            assertNull(model.state.draft)
            assertEquals(false, model.state.isSaving)
            assertNotNull(model.state.successMessage)
        }

    @Test
    fun save_withAnInvalidClass_neverReachesTheWire() =
        runTest {
            // The server rejects anything outside A/B/C with a 400. The picker cannot produce such a
            // value, so this is the defensive leg — it must fail fast, not post and hope.
            val config = FakeShopConfigApiClient()
            val model = vm(config)
            model.refresh()
            model.startEdit()
            model.updateRules(ProductType.FOOD, VatRules(default = "X"))
            model.save()
            assertTrue(config.vatMappingRequests.isEmpty())
            assertIs<UiError.Unknown>(model.state.error)
            assertNotNull(model.state.draft)
        }

    @Test
    fun saveFailure_keepsTheDraftOpen() =
        runTest {
            var calls = 0
            val config = FakeShopConfigApiClient(onCall = { if (++calls > 1) throw NetworkApiException(RuntimeException("down")) })
            val model = vm(config)
            model.refresh()
            model.startEdit()
            model.save()
            assertIs<UiError.Network>(model.state.error)
            assertNotNull(model.state.draft)
            assertEquals(false, model.state.isSaving)
        }
}
