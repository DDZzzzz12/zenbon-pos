package org.example.project.admin.features.customexport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.admin.features.customexport.presentation.CustomExportViewModel
import org.example.project.admin.features.customexport.presentation.RunBlockReason
import org.example.project.admin.features.customexport.presentation.defaultHeading
import org.example.project.admin.features.customexport.presentation.runBlockReason
import org.example.project.core.domain.dto.customexport.CustomExportDto
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Column headings end up in the operator's CSV, so what the editor *shows* for a stored heading is
 * pinned here — and that showing it never rewrites the stored definition.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefinitionHeadingsTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a heading equal to the raw key shows the catalogue label`() {
        assertEquals("Artikelnummer", defaultHeading("produkt.product_number", "produkt.product_number", "Artikelnummer"))
    }

    @Test
    fun `a heading equal to the last segment of the key shows the catalogue label`() {
        assertEquals("Artikelnummer", defaultHeading("produkt.product_number", "product_number", "Artikelnummer"))
        assertEquals("Name", defaultHeading("supplier:by:partner_id.name", "name", "Name"))
        assertEquals("Anzahl", defaultHeading("debtor:by:partner_id.sales_invoice:by:debtor_id~count", "count", "Anzahl"))
    }

    @Test
    fun `a blank heading shows the catalogue label`() {
        assertEquals("Preis", defaultHeading("produkt.price", "", "Preis"))
        assertEquals("Preis", defaultHeading("produkt.price", "   ", "Preis"))
    }

    @Test
    fun `an operator-written heading is kept`() {
        assertEquals("Art.-Nr.", defaultHeading("produkt.product_number", "Art.-Nr.", "Artikelnummer"))
        assertEquals("Name", defaultHeading("produkt.name", "Name", "Name"))
    }

    @Test
    fun `an unresolved field keeps its stored heading`() {
        assertEquals("spalte_gibt_es_nicht", defaultHeading("produkt.spalte_gibt_es_nicht", "spalte_gibt_es_nicht", null))
    }

    @Test
    fun `run is blocked until saved, then until repaired`() {
        assertEquals(RunBlockReason.SAVE_FIRST, runBlockReason(saved = false, dirty = true, broken = false))
        assertEquals(RunBlockReason.SAVE_FIRST, runBlockReason(saved = true, dirty = true, broken = false))
        assertEquals(RunBlockReason.FIX_FIELD, runBlockReason(saved = true, dirty = false, broken = true))
        assertNull(runBlockReason(saved = true, dirty = false, broken = false))
    }

    private val subject =
        CustomExportDto.SubjectDto(
            key = "produkt",
            label = "Produkte",
            fields =
                listOf(
                    CustomExportDto.FieldDto("produkt.product_number", "Artikelnummer", "TEXT"),
                    CustomExportDto.FieldDto("produkt.name", "Name", "TEXT"),
                ),
            toggles = emptyList(),
            hasDateFilter = false,
        )

    private val stored =
        CustomExportDto.DefinitionDto(
            id = 1,
            name = "Produktliste",
            subjectKey = "produkt",
            fields =
                listOf(
                    CustomExportDto.DefinitionFieldDto("produkt.product_number", 0, "product_number"),
                    CustomExportDto.DefinitionFieldDto("produkt.name", 1, "Bezeichnung"),
                ),
        )

    private fun api() =
        FakeCustomExportApiClient().apply {
            subjects = listOf(subject)
            definitions = listOf(stored)
        }

    @Test
    fun `opening a definition displays the label but is not a change and stores nothing`() =
        runTest(dispatcher) {
            val api = api()
            val vm = CustomExportViewModel(api)
            vm.load()
            advanceUntilIdle()

            vm.editDefinition(1)

            assertEquals(
                listOf("Artikelnummer", "Bezeichnung"),
                vm.state.draft
                    ?.fields
                    ?.map { it.heading },
            )
            assertFalse(vm.state.isDraftDirty)
            assertEquals(
                "product_number",
                api.definitions
                    .single()
                    .fields
                    .first()
                    .heading,
            )
            assertTrue(api.saves.isEmpty() && api.updates.isEmpty())
        }

    @Test
    fun `editing a heading makes the form dirty and saving sends the shown heading`() =
        runTest(dispatcher) {
            val api = api()
            val vm = CustomExportViewModel(api)
            vm.load()
            advanceUntilIdle()
            vm.editDefinition(1)

            vm.updateHeading("produkt.name", "Titel")

            assertTrue(vm.state.isDraftDirty)
            vm.save()
            advanceUntilIdle()
            assertEquals(
                listOf("Artikelnummer", "Titel"),
                api.updates
                    .single()
                    .second.fields
                    .map { it.heading },
            )
        }

    @Test
    fun `a new draft is always dirty`() =
        runTest(dispatcher) {
            val vm = CustomExportViewModel(api())
            vm.load()
            advanceUntilIdle()

            vm.startDraft("produkt")

            assertTrue(vm.state.isDraftDirty)
        }
}
