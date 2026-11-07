package org.example.project.admin.features.customexport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.admin.features.customexport.presentation.CustomExportViewModel
import org.example.project.core.domain.dto.customexport.CustomExportDto
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The draft is unsaved only when it differs from how it was opened: an opened definition, a fresh
 * draft, and an edit that was undone are all clean. This is what the leave guard asks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CustomExportDirtyDraftTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val subject =
        CustomExportDto.SubjectDto(
            key = "partner",
            label = "Geschäftspartner",
            fields =
                listOf(
                    CustomExportDto.FieldDto(key = "partner.name", label = "Name", type = "TEXT"),
                    CustomExportDto.FieldDto(key = "partner.city", label = "Ort", type = "TEXT"),
                ),
            toggles = emptyList(),
            hasDateFilter = true,
        )

    private val definition =
        CustomExportDto.DefinitionDto(
            id = 7,
            name = "Kreditoren",
            subjectKey = "partner",
            fields = listOf(CustomExportDto.DefinitionFieldDto("partner.name", 0, "Name")),
        )

    private fun loaded(api: FakeCustomExportApiClient = api()): CustomExportViewModel {
        val vm = CustomExportViewModel(api)
        vm.load()
        dispatcher.scheduler.advanceUntilIdle()
        return vm
    }

    private fun api() =
        FakeCustomExportApiClient().apply {
            subjects = listOf(subject)
            definitions = listOf(definition)
        }

    @Test
    fun `no draft is clean`() =
        runTest(dispatcher) {
            assertFalse(loaded().state.isDirty)
        }

    @Test
    fun `a freshly started draft is clean until something is entered`() =
        runTest(dispatcher) {
            val vm = loaded()
            vm.startDraft()
            assertFalse(vm.state.isDirty)

            vm.updateName("Neu")
            assertTrue(vm.state.isDirty)

            vm.updateName("")
            assertFalse(vm.state.isDirty)
        }

    @Test
    fun `an opened definition is clean, edited is dirty, and undoing the edit is clean`() =
        runTest(dispatcher) {
            val vm = loaded()
            vm.editDefinition(7)
            assertFalse(vm.state.isDirty)

            vm.toggleField("partner.city")
            assertTrue(vm.state.isDirty)

            vm.toggleField("partner.city")
            assertFalse(vm.state.isDirty)
        }

    @Test
    fun `closing the draft drops the draft and its baseline`() =
        runTest(dispatcher) {
            val vm = loaded()
            vm.editDefinition(7)
            vm.updateName("Anders")

            vm.closeDraft()

            assertFalse(vm.state.isDirty)
            assertNull(vm.state.draft)
            assertNull(vm.state.baseline)
        }

    @Test
    fun `a successful save leaves no dirty draft behind`() =
        runTest(dispatcher) {
            val vm = loaded()
            vm.editDefinition(7)
            vm.updateName("Anders")

            vm.save()
            advanceUntilIdle()

            assertFalse(vm.state.isDirty)
            assertNull(vm.state.draft)
        }
}
