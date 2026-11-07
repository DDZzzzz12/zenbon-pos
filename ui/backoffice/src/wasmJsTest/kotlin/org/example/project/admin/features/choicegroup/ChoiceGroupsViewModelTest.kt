package org.example.project.admin.features.choicegroup

import kotlinx.coroutines.test.runTest
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.choicegroup.presentation.ChoiceGroupField
import org.example.project.admin.features.choicegroup.presentation.ChoiceGroupsState
import org.example.project.admin.features.choicegroup.presentation.ChoiceGroupsViewModel
import org.example.project.admin.features.choicegroup.presentation.GroupEditor
import org.example.project.admin.features.choicegroup.presentation.validateGroupDraft
import org.example.project.admin.features.extras.FakeProductExtrasApiClient
import org.example.project.core.domain.dto.product.ChoiceGroupDto
import org.example.project.core.domain.dto.product.ProductExtrasDto
import org.example.project.core.presentation.strings.keys.BackOfficeErrorKeys
import org.example.project.core.presentation.strings.keys.ChoiceGroupKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

private fun extra(
    id: Int,
    name: String,
) = ProductExtrasDto.ProductExtrasResponseDto(id = id, name = name, price = "0.50", isActive = true)

private fun group(
    id: Int,
    name: String,
) = ChoiceGroupDto.ChoiceGroupResponseDto(
    id = id,
    name = name,
    minSelections = 0,
    maxSelections = 1,
    isActive = true,
    updatedAt = "2026-07-11",
    options = emptyList(),
)

private fun vm(
    groups: List<ChoiceGroupDto.ChoiceGroupResponseDto> = emptyList(),
    extras: List<ProductExtrasDto.ProductExtrasResponseDto> = emptyList(),
    fakeGroups: FakeChoiceGroupApiClient = FakeChoiceGroupApiClient(groups),
) = ChoiceGroupsViewModel(fakeGroups, FakeProductExtrasApiClient(extras)) to fakeGroups

class ChoiceGroupsViewModelTest {
    @Test fun load_populatesGroupsAndExtras() =
        runTest {
            val (model, _) = vm(groups = listOf(group(1, "Size")), extras = listOf(extra(10, "Cheese")))
            model.refresh()
            val data = assertIs<ChoiceGroupsState.Data>(model.state)
            assertEquals(listOf("Size"), data.groups.map { it.name })
            assertEquals(listOf("Cheese"), data.extras.map { it.name })
        }

    @Test fun createGroup_withOptions_buildsRequest() =
        runTest {
            val (model, fake) = vm(extras = listOf(extra(10, "Cheese"), extra(11, "Bacon")))
            model.refresh()
            model.openCreate()
            model.onField { it.copy(name = "Toppings", maxSelections = "2") }
            model.addOption(10) // Cheese
            model.addOption(11) // Bacon
            model.setOptionPriceDelta(11, "1.00")
            model.setOptionDefault(10, true)
            model.saveGroup()
            val req = assertNotNull(fake.created)
            assertEquals("Toppings", req.name)
            assertEquals(2, req.maxSelections)
            assertEquals(listOf(10, 11), req.options.map { it.optionId }) // order preserved (sortPosition)
            assertEquals(listOf(0, 1), req.options.map { it.sortPosition })
            assertEquals("1.00", req.options.first { it.optionId == 11 }.priceDelta)
            assertEquals(true, req.options.first { it.optionId == 10 }.isDefault)
        }

    @Test fun editGroup_mapsExistingOptions() =
        runTest {
            val existing =
                group(5, "Size").copy(
                    options =
                        listOf(
                            ChoiceGroupDto.OptionResponseDto(
                                optionId = 10,
                                name = "Cheese",
                                priceDelta = "0.50",
                                sortPosition = 0,
                                isDefault = true,
                            ),
                        ),
                )
            val (model, fake) = vm(groups = listOf(existing), extras = listOf(extra(10, "Cheese")))
            model.refresh()
            model.openEdit(existing)
            model.onField { it.copy(name = "Sizes") }
            model.saveGroup()
            assertEquals(5, fake.updatedId)
            assertEquals("Sizes", assertNotNull(fake.updated).name)
            assertEquals(listOf(10), fake.updated!!.options.map { it.optionId })
        }

    @Test fun removeAndReorderOptions() =
        runTest {
            val (model, fake) = vm(extras = listOf(extra(10, "A"), extra(11, "B"), extra(12, "C")))
            model.refresh()
            model.openCreate()
            model.onField { it.copy(name = "G") }
            model.addOption(10)
            model.addOption(11)
            model.addOption(12)
            model.removeOption(11) // A, C
            model.moveOption(12, up = true) // C, A
            model.saveGroup()
            assertEquals(listOf(12, 10), assertNotNull(fake.created).options.map { it.optionId })
        }

    @Test fun deleteGroup_callsApi() =
        runTest {
            val (model, fake) = vm(groups = listOf(group(9, "Size")))
            model.refresh()
            model.delete(9)
            assertEquals(9, fake.deletedId)
        }

    @Test fun save_blankName_setsFieldError() =
        runTest {
            val (model, fake) = vm()
            model.refresh()
            model.openCreate()
            model.onField { it.copy(name = "") }
            model.saveGroup()
            assertEquals(null, fake.created)
            val refused = assertNotNull(model.editor)
            assertEquals(mapOf(ChoiceGroupField.NAME to AdminStringKeys.ProductFieldRequired), refused.fieldErrors)
            assertEquals(1, refused.validationAttempt)
            assertEquals(null, refused.fieldError, "a validation refusal is not a form-level error")
        }

    @Test fun save_emptyCreateForm_isRefusedAtTheFields_andRetriesScrollAgain() =
        runTest {
            val (model, fake) = vm()
            model.refresh()
            model.openCreate()
            model.onField { it.copy(minSelections = "3", maxSelections = "2") }
            model.saveGroup()
            model.saveGroup()
            assertEquals(null, fake.created)
            val refused = assertNotNull(model.editor)
            assertEquals(setOf(ChoiceGroupField.NAME, ChoiceGroupField.SELECTION_RANGE), refused.fieldErrors.keys)
            assertEquals(ChoiceGroupKeys.invalidSelectionRange, refused.fieldErrors[ChoiceGroupField.SELECTION_RANGE])
            assertEquals(2, refused.validationAttempt)

            // Editing either half of the range clears the range error; the name error stays.
            model.onField { it.copy(maxSelections = "5") }
            assertEquals(setOf(ChoiceGroupField.NAME), assertNotNull(model.editor).fieldErrors.keys)
            model.onField { it.copy(name = "Toppings") }
            model.saveGroup()
            assertEquals(3, assertNotNull(fake.created).minSelections)
        }

    @Test fun validateGroupDraft_maxBelowOne_isARangeError() =
        assertEquals(
            setOf(ChoiceGroupField.SELECTION_RANGE),
            validateGroupDraft(GroupEditor(name = "G", minSelections = "0", maxSelections = "0")).keys,
        )

    @Test fun validateGroupDraft_blankCountsUseTheDefaults() =
        assertEquals(emptyMap(), validateGroupDraft(GroupEditor(name = "G", minSelections = "", maxSelections = "")))

    // ----- admin21 NOTE-e21 / NOTE-d21 / NOTE-c21 -----

    @Test fun editGroup_seedsDeltaWithoutDbScalePadding() =
        runTest {
            val existing =
                group(5, "Size").copy(
                    options =
                        listOf(
                            ChoiceGroupDto.OptionResponseDto(optionId = 10, name = "Cheese", priceDelta = "1.5000", sortPosition = 0, isDefault = false),
                        ),
                )
            val (model, _) = vm(groups = listOf(existing), extras = listOf(extra(10, "Cheese")))
            model.refresh()
            model.openEdit(existing)
            assertEquals(
                "1.50",
                model.editor!!
                    .options
                    .single()
                    .priceDelta,
            )
        }

    @Test fun save_sendsACommaDeltaCanonical() =
        runTest {
            val (model, fake) = vm(extras = listOf(extra(10, "Cheese")))
            model.refresh()
            model.openCreate()
            model.onField { it.copy(name = "G") }
            model.addOption(10)
            model.setOptionPriceDelta(10, "1,50")
            model.saveGroup()
            assertEquals("1.50", assertNotNull(fake.created).options.single().priceDelta)
        }

    @Test fun save_unreadableDelta_setsPriceError_doesNotCallApi() =
        runTest {
            val (model, fake) = vm(extras = listOf(extra(10, "Cheese")))
            model.refresh()
            model.openCreate()
            model.onField { it.copy(name = "G") }
            model.addOption(10)
            model.setOptionPriceDelta(10, "1,5x")
            model.saveGroup()
            assertEquals(null, fake.created)
            assertEquals(BackOfficeErrorKeys.priceInvalid, model.editor?.fieldErrors?.get(ChoiceGroupField.OPTION_PRICES))

            // Correcting the delta clears the error and the save goes through.
            model.setOptionPriceDelta(10, "1,50")
            assertEquals(emptyMap(), assertNotNull(model.editor).fieldErrors)
            model.saveGroup()
            assertEquals("1.50", assertNotNull(fake.created).options.single().priceDelta)
        }

    @Test fun save_removingTheUnreadableRow_clearsThePriceError() =
        runTest {
            val (model, _) = vm(extras = listOf(extra(10, "Cheese")))
            model.refresh()
            model.openCreate()
            model.onField { it.copy(name = "G") }
            model.addOption(10)
            model.setOptionPriceDelta(10, "x")
            model.saveGroup()
            model.removeOption(10)
            assertEquals(emptyMap(), assertNotNull(model.editor).fieldErrors)
        }

    @Test fun availableExtras_skipsDeletedExtras() =
        runTest {
            val (model, _) = vm(extras = listOf(extra(10, "Cheese"), extra(11, "Gone").copy(isActive = false)))
            model.refresh()
            model.openCreate()
            assertEquals(listOf(10), model.availableExtras().map { it.id })
        }
}
