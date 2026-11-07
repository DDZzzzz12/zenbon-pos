package org.example.project.admin.features.extras

import kotlinx.coroutines.test.runTest
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.choicegroup.FakeChoiceGroupApiClient
import org.example.project.admin.features.extras.presentation.ExtraEditor
import org.example.project.admin.features.extras.presentation.ExtraField
import org.example.project.admin.features.extras.presentation.ExtrasState
import org.example.project.admin.features.extras.presentation.ExtrasViewModel
import org.example.project.admin.features.extras.presentation.withEditedErrorsCleared
import org.example.project.core.domain.dto.product.ChoiceGroupDto
import org.example.project.core.domain.dto.product.ProductExtrasDto
import org.example.project.core.network.error.NetworkApiException
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.keys.BackOfficeErrorKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun extra(
    id: Int,
    name: String,
    price: String,
) = ProductExtrasDto.ProductExtrasResponseDto(id = id, name = name, price = price, isActive = true)

class ExtrasViewModelTest {
    @Test
    fun load_populatesData() =
        runTest {
            val vm = ExtrasViewModel(FakeProductExtrasApiClient(listOf(extra(1, "Cheese", "0.50"))), FakeChoiceGroupApiClient())
            vm.refresh()
            val data = assertIs<ExtrasState.Data>(vm.state)
            assertEquals(listOf("Cheese"), data.extras.map { it.name })
        }

    @Test
    fun createExtra_callsApi_andReloads() =
        runTest {
            val fake = FakeProductExtrasApiClient()
            val vm = ExtrasViewModel(fake, FakeChoiceGroupApiClient())
            vm.refresh()
            vm.openCreate()
            vm.onField { it.copy(name = "Bacon", price = "1.20") }
            vm.save()
            assertEquals("Bacon", assertNotNull(fake.created).name)
        }

    @Test
    fun editExtra_callsUpdate() =
        runTest {
            val fake = FakeProductExtrasApiClient(listOf(extra(5, "Cheese", "0.50")))
            val vm = ExtrasViewModel(fake, FakeChoiceGroupApiClient())
            vm.refresh()
            vm.openEdit(extra(5, "Cheese", "0.50"))
            vm.onField { it.copy(name = "Extra cheese") }
            vm.save()
            assertEquals(5L, fake.updatedId)
            assertEquals("Extra cheese", assertNotNull(fake.updated).name)
        }

    @Test
    fun deleteExtra_callsApi() =
        runTest {
            val fake = FakeProductExtrasApiClient(listOf(extra(9, "Cheese", "0.50")))
            val vm = ExtrasViewModel(fake, FakeChoiceGroupApiClient())
            vm.refresh()
            vm.delete(9)
            assertEquals(9L, fake.deletedId)
        }

    @Test
    fun save_blankName_setsFieldError_doesNotCallApi() =
        runTest {
            val fake = FakeProductExtrasApiClient()
            val vm = ExtrasViewModel(fake, FakeChoiceGroupApiClient())
            vm.refresh()
            vm.openCreate()
            vm.onField { it.copy(name = "", price = "1.00") }
            vm.save()
            assertEquals(null, fake.created)
            assertEquals(AdminStringKeys.ProductFieldRequired, vm.editor?.fieldErrors?.get(ExtraField.NAME))
        }

    @Test
    fun loadFailure_yieldsNetworkError() =
        runTest {
            val vm = ExtrasViewModel(FakeProductExtrasApiClient(onError = { throw NetworkApiException(RuntimeException("down")) }), FakeChoiceGroupApiClient())
            vm.refresh()
            assertTrue(vm.state is ExtrasState.Error)
            assertIs<UiError.Network>((vm.state as ExtrasState.Error).error)
        }

    // ----- admin21 NOTE-c21: an extra a required choice group needs -----

    private fun requiredGroup(vararg optionIds: Int) =
        ChoiceGroupDto.ChoiceGroupResponseDto(
            id = 3,
            name = "Größe",
            minSelections = 1,
            maxSelections = 1,
            isActive = true,
            updatedAt = "2026-10-03",
            options = optionIds.map { ChoiceGroupDto.OptionResponseDto(optionId = it, name = "O$it", priceDelta = "0", sortPosition = 0, isDefault = false) },
        )

    @Test
    fun delete_onlyOptionOfRequiredGroup_isRefusedLocally() =
        runTest {
            val fake = FakeProductExtrasApiClient(listOf(extra(9, "Klein", "1.50")))
            val vm = ExtrasViewModel(fake, FakeChoiceGroupApiClient(listOf(requiredGroup(9))))
            vm.refresh()
            assertEquals(listOf("Größe"), vm.requiredGroupsNeeding(9))
            vm.delete(9)
            assertEquals(null, fake.deletedId, "the delete must not be sent")
        }

    @Test
    fun delete_spareOption_isSent() =
        runTest {
            val fake = FakeProductExtrasApiClient(listOf(extra(9, "Klein", "1.50"), extra(10, "Groß", "2.00")))
            val vm = ExtrasViewModel(fake, FakeChoiceGroupApiClient(listOf(requiredGroup(9, 10))))
            vm.refresh()
            assertEquals(emptyList(), vm.requiredGroupsNeeding(9))
            vm.delete(9)
            assertEquals(9L, fake.deletedId)
        }

    @Test
    fun delete_optionOfInactiveOrOptionalGroup_isNotGuarded() =
        runTest {
            val groups = listOf(requiredGroup(9).copy(isActive = false), requiredGroup(9).copy(id = 4, minSelections = 0))
            val vm = ExtrasViewModel(FakeProductExtrasApiClient(listOf(extra(9, "Klein", "1.50"))), FakeChoiceGroupApiClient(groups))
            vm.refresh()
            assertEquals(emptyList(), vm.requiredGroupsNeeding(9))
        }

    @Test
    fun deactivate_onlyOptionOfRequiredGroup_isRefusedLocally() =
        runTest {
            val fake = FakeProductExtrasApiClient(listOf(extra(9, "Klein", "1.50")))
            val vm = ExtrasViewModel(fake, FakeChoiceGroupApiClient(listOf(requiredGroup(9))))
            vm.refresh()
            vm.openEdit(extra(9, "Klein", "1.50"))
            vm.onField { it.copy(isActive = false) }
            vm.save()
            assertEquals(null, fake.updated)
            assertEquals("required_group", vm.editor?.fieldError)
        }

    // ----- admin21 NOTE-e21 / NOTE-d21: price seeding and sending -----

    @Test
    fun edit_seedsPriceWithoutDbScalePadding_andSendsACommaPriceCanonical() =
        runTest {
            val fake = FakeProductExtrasApiClient(listOf(extra(5, "Cheese", "1.5000")))
            val vm = ExtrasViewModel(fake, FakeChoiceGroupApiClient())
            vm.refresh()
            vm.openEdit(extra(5, "Cheese", "1.5000"))
            assertEquals("1.50", vm.editor?.price)
            vm.onField { it.copy(price = "2,00") }
            vm.save()
            assertEquals("2.00", assertNotNull(fake.updated).price)
        }

    @Test
    fun save_unreadablePrice_setsPriceError_doesNotCallApi() =
        runTest {
            val fake = FakeProductExtrasApiClient()
            val vm = ExtrasViewModel(fake, FakeChoiceGroupApiClient())
            vm.refresh()
            vm.openCreate()
            vm.onField { it.copy(name = "Bacon", price = "1,2x") }
            vm.save()
            assertEquals(null, fake.created)
            assertEquals(BackOfficeErrorKeys.priceInvalid, vm.editor?.fieldErrors?.get(ExtraField.PRICE))
        }

    @Test
    fun withEditedErrorsCleared_clearsOnlyTheChangedFields() {
        val before =
            ExtraEditor(
                fieldErrors =
                    mapOf(
                        ExtraField.NAME to AdminStringKeys.ProductFieldRequired,
                        ExtraField.PRICE to BackOfficeErrorKeys.priceInvalid,
                    ),
            )
        val after = before.copy(name = "Bacon").withEditedErrorsCleared(before)
        assertEquals(setOf(ExtraField.PRICE), after.fieldErrors.keys)
    }

    @Test
    fun save_validationAttempt_incrementsOnEachRefusedSave() =
        runTest {
            val vm = ExtrasViewModel(FakeProductExtrasApiClient(), FakeChoiceGroupApiClient())
            vm.refresh()
            vm.openCreate()
            vm.save()
            assertEquals(1, vm.editor?.validationAttempt)
            vm.save()
            assertEquals(2, vm.editor?.validationAttempt)
        }
}
