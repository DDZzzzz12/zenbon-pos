package org.example.project.admin.features.customexport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.admin.features.customexport.presentation.CustomExportViewModel
import org.example.project.admin.features.customexport.presentation.DraftField
import org.example.project.core.data.remote.CustomExportRunResult
import org.example.project.core.domain.dto.customexport.CustomExportDto
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.FileDownload
import org.example.project.core.network.error.PosApiException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The open draft's columns, in the order the operator arranged them. */
private fun CustomExportViewModel.draftFields(): List<DraftField> {
    val draft = state.draft
    return draft?.fields.orEmpty()
}

private fun CustomExportViewModel.draftKeys() = draftFields().map { it.fieldKey }

private fun field(
    key: String,
    label: String,
) = CustomExportDto.FieldDto(key = key, label = label, type = "TEXT")

private val partnerSubject =
    CustomExportDto.SubjectDto(
        key = "partner",
        label = "Geschäftspartner",
        fields =
            listOf(
                field("partner.name", "Name"),
                field("partner.vat_id", "USt-IdNr."),
                field("partner.city", "Ort"),
            ),
        toggles = listOf(CustomExportDto.ToggleDto(key = "only_open", label = "Nur offene")),
        hasDateFilter = true,
    )

private val savedDefinition =
    CustomExportDto.DefinitionDto(
        id = 7,
        name = "Kreditoren",
        subjectKey = "partner",
        fields = listOf(CustomExportDto.DefinitionFieldDto("partner.name", 0, "Name")),
    )

/**
 * A definition naming a column the catalogue has since dropped. `partner.iban` is not in
 * [partnerSubject], which is exactly what makes it broken.
 */
private val brokenDefinition =
    CustomExportDto.DefinitionDto(
        id = 8,
        name = "Alte Kreditorenliste",
        subjectKey = "partner",
        fields =
            listOf(
                CustomExportDto.DefinitionFieldDto("partner.name", 0, "Name"),
                CustomExportDto.DefinitionFieldDto("partner.iban", 1, "IBAN"),
            ),
        isBroken = true,
        unknownKeys = listOf("partner.iban"),
    )

/**
 * The custom export builder's view model.
 *
 * The load and edit tests are ordinary; the save test is the load-bearing one. Column order is held
 * only as list order in the draft, so the request that goes out is the only place the index-is-the-
 * position rule can be observed at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CustomExportViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun api() =
        FakeCustomExportApiClient().apply {
            subjects = listOf(partnerSubject)
            definitions = listOf(savedDefinition, brokenDefinition)
        }

    /** A view model with the catalogue loaded and an empty draft open on the partner subject. */
    private fun TestScope.loadedWithDraft(api: FakeCustomExportApiClient): CustomExportViewModel {
        val vm = CustomExportViewModel(api)
        vm.load()
        advanceUntilIdle()
        vm.startDraft("partner")
        return vm
    }

    @Test
    fun `loading brings back the catalogue and the saved definitions`() =
        runTest(dispatcher) {
            val vm = CustomExportViewModel(api())

            vm.load()
            advanceUntilIdle()

            assertEquals(listOf("partner"), vm.state.subjects.map { it.key })
            assertEquals(listOf(7L, 8L), vm.state.definitions.map { it.id })
            assertTrue(!vm.state.isLoading)
            assertNull(vm.state.error)
        }

    /**
     * The catalogue's label becomes the heading, so an operator who never edits one still gets a
     * readable column instead of `partner.vat_id`.
     */
    @Test
    fun `ticking a column names it after the catalogue's label`() =
        runTest(dispatcher) {
            val vm = loadedWithDraft(api())

            vm.toggleField("partner.vat_id")

            assertEquals(listOf("partner.vat_id"), vm.draftKeys())
            assertEquals("USt-IdNr.", vm.draftFields().single().heading)
        }

    @Test
    fun `un-ticking a column removes it`() =
        runTest(dispatcher) {
            val vm = loadedWithDraft(api())
            vm.toggleField("partner.name")
            vm.toggleField("partner.vat_id")

            vm.toggleField("partner.name")

            assertEquals(listOf("partner.vat_id"), vm.draftKeys())
        }

    @Test
    fun `moving a column up swaps it with the one before`() =
        runTest(dispatcher) {
            val vm = loadedWithDraft(api())
            vm.toggleField("partner.name")
            vm.toggleField("partner.vat_id")

            vm.moveFieldUp(1)

            assertEquals(
                listOf("partner.vat_id", "partner.name"),
                vm.draftKeys(),
            )
        }

    /** The first column has nowhere to go; the screen may call this without guarding it first. */
    @Test
    fun `moving the first column up changes nothing`() =
        runTest(dispatcher) {
            val vm = loadedWithDraft(api())
            vm.toggleField("partner.name")
            vm.toggleField("partner.vat_id")

            vm.moveFieldUp(0)

            assertEquals(
                listOf("partner.name", "partner.vat_id"),
                vm.draftKeys(),
            )
        }

    /**
     * Positions are 0..n-1 **in the draft's order**, not the catalogue's.
     *
     * The three columns are ticked in an order the catalogue does not have (`city` before `vat_id`)
     * and then reordered again, so a save that emitted catalogue order would produce a different
     * key-to-position pairing here and fail.
     */
    @Test
    fun `saving numbers the columns from the draft's own order`() =
        runTest(dispatcher) {
            val api = api()
            val vm = loadedWithDraft(api)
            vm.updateName("Kreditorenliste")
            vm.toggleField("partner.city")
            vm.toggleField("partner.name")
            vm.toggleField("partner.vat_id")
            vm.moveFieldUp(2)

            vm.save()
            advanceUntilIdle()

            val sent = api.saves.single()
            assertEquals("Kreditorenliste", sent.name)
            assertEquals("partner", sent.subjectKey)
            assertEquals(
                listOf("partner.city" to 0, "partner.vat_id" to 1, "partner.name" to 2),
                sent.fields.map { it.fieldKey to it.position },
            )
        }

    /**
     * The server compares both bounds inclusively, so the upper one has to reach the **end** of its
     * day. At midnight the range is half-open: everything booked after 00:00 on the last day the
     * operator asked for is missing, the file still looks complete, and the loss shows up weeks later
     * as totals that do not reconcile.
     *
     * This test exists to stop the two suffixes being "tidied" into one.
     */
    @Test
    fun `the upper date bound covers the whole final day`() =
        runTest(dispatcher) {
            val api = api()
            val vm = loadedWithDraft(api)
            vm.toggleField("partner.name")

            vm.setDateRange("2026-03-01", "2026-03-31")
            vm.save()
            advanceUntilIdle()

            val sent = api.saves.single()
            assertEquals("2026-03-01T00:00:00", sent.dateFrom)
            assertEquals("2026-03-31T23:59:59", sent.dateTo)
        }

    /**
     * A stored bound handed straight back must not gain a second time half. The screen re-sends the
     * untouched bound whenever the other one changes, so this is the ordinary path, not an edge case.
     */
    @Test
    fun `re-setting a bound from a stored value does not stack time halves`() =
        runTest(dispatcher) {
            val vm = loadedWithDraft(api())

            vm.setDateRange("2026-03-01", "2026-03-31")
            val storedFrom = vm.state.draft?.dateFrom
            vm.setDateRange(storedFrom, "2026-04-30")

            assertEquals("2026-03-01T00:00:00", vm.state.draft?.dateFrom)
            assertEquals("2026-04-30T23:59:59", vm.state.draft?.dateTo)
        }

    /** A cleared field means "no bound" — null on the wire, never an empty string the server rejects. */
    @Test
    fun `a blank date clears the bound instead of sending an empty string`() =
        runTest(dispatcher) {
            val vm = loadedWithDraft(api())

            vm.setDateRange("2026-03-01", "2026-03-31")
            vm.setDateRange("", null)

            assertNull(vm.state.draft?.dateFrom)
            assertNull(vm.state.draft?.dateTo)
        }

    /**
     * A columnless export is refused by the server too, but the client already knows: a round trip to
     * be told so is a worse experience than a disabled button.
     */
    @Test
    fun `saving a draft with no columns never reaches the server`() =
        runTest(dispatcher) {
            val api = api()
            val vm = loadedWithDraft(api)
            vm.updateName("Leer")

            vm.save()
            advanceUntilIdle()

            assertTrue(api.saves.isEmpty(), "an empty definition must not be sent")
            assertTrue(!vm.state.isSaving)
        }

    /**
     * Editing a saved definition writes it back under its own id.
     *
     * Before the update route existed the editor POSTed every draft, so each edit left the original
     * untouched and stored a second definition beside it — the operator's list grew a copy per edit.
     */
    @Test
    fun `saving an opened definition updates it in place instead of creating a copy`() =
        runTest(dispatcher) {
            val api = api()
            val vm = CustomExportViewModel(api)
            vm.load()
            advanceUntilIdle()
            vm.editDefinition(7)
            vm.updateName("Kreditoren (neu)")
            vm.toggleField("partner.vat_id")

            vm.save()
            advanceUntilIdle()

            assertTrue(api.saves.isEmpty(), "an edit must not POST a new definition")
            val (id, sent) = api.updates.single()
            assertEquals(7L, id)
            assertEquals("Kreditoren (neu)", sent.name)
            assertEquals(listOf("partner.name" to 0, "partner.vat_id" to 1), sent.fields.map { it.fieldKey to it.position })
            assertNull(vm.state.draft, "a successful update closes the editor")
        }

    /** A draft started from scratch has no id, so it is still created, never sent as an update. */
    @Test
    fun `saving a new draft creates a definition`() =
        runTest(dispatcher) {
            val api = api()
            val vm = loadedWithDraft(api)
            vm.updateName("Neu")
            vm.toggleField("partner.name")

            vm.save()
            advanceUntilIdle()

            assertEquals(1, api.saves.size)
            assertTrue(api.updates.isEmpty(), "a new draft has nothing to update")
        }

    /** A spinner that never stops is worse than an error message. */
    @Test
    fun `a failed run reports the error and stops the spinner`() =
        runTest(dispatcher) {
            val api = api()
            val vm = CustomExportViewModel(api)
            vm.load()
            advanceUntilIdle()
            api.failWith = PosApiException(PosErrorCode.INTERNAL_ERROR, 500, "boom", null)

            vm.run(7)
            advanceUntilIdle()

            assertTrue(vm.state.error != null, "the operator must be told the export did not run")
            assertTrue(!vm.state.isRunning)
            assertNull(vm.state.truncatedRowCount)
        }

    /**
     * A broken definition opens with its dead column still on screen and named as dead.
     *
     * The row has to remain visible to be removable: an operator told "this export uses a field that
     * no longer exists" and shown a list the field is missing from has been given no way to act, and
     * a silent re-save would drop the column without them ever learning which one it was.
     */
    @Test
    fun `opening a broken definition keeps the dead column and names it`() =
        runTest(dispatcher) {
            val vm = CustomExportViewModel(api())
            vm.load()
            advanceUntilIdle()

            vm.editDefinition(8)

            assertEquals(listOf("partner.name", "partner.iban"), vm.draftKeys())
            assertEquals(setOf("partner.iban"), vm.state.draft?.unresolvedKeys)
        }

    /**
     * A short file is still useful, but only if the operator knows it is short.
     *
     * A successful run really does call [org.example.project.core.utils.FileSaver], so this test
     * fires a browser download in headless Chrome. That is silent and harmless; the saver is not
     * injected because a seam with no other purpose is not worth the indirection.
     */
    @Test
    fun `a truncated run keeps the row count that was written`() =
        runTest(dispatcher) {
            val api = api()
            api.runResult =
                CustomExportRunResult(
                    download = FileDownload(bytes = "Name\n".encodeToByteArray(), fileName = "partner.csv"),
                    rowCount = 5000,
                    truncated = true,
                )
            val vm = CustomExportViewModel(api)
            vm.load()
            advanceUntilIdle()

            vm.run(7)
            advanceUntilIdle()

            assertEquals(5000, vm.state.truncatedRowCount)
            assertTrue(!vm.state.isRunning)
            assertNull(vm.state.error)
        }
}
