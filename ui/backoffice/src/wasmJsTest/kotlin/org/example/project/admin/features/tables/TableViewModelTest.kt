package org.example.project.admin.features.tables

import kotlinx.coroutines.test.runTest
import org.example.project.admin.features.tables.presentation.TableViewModel
import org.example.project.core.domain.dto.table.RTableDto
import org.example.project.core.network.error.NetworkApiException
import org.example.project.core.presentation.error.UiError
import org.example.project.features.settings.presentation.state.TableSortColumn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun table(
    id: Long,
    number: String,
    locationTypeId: Long = 1L,
    locationTypeName: String = "Indoor",
) = RTableDto.RTableResponseDto(
    id = id,
    tableNumber = number,
    seats = 4,
    locationTypeId = locationTypeId,
    locationTypeName = locationTypeName,
    locationTypeEnglishName = locationTypeName,
    locationTypeChineseName = locationTypeName,
    isVirtual = false,
    isTakeaway = false,
    tableStatus = "FREE",
    currentSessionId = null,
    hasActiveSession = false,
    positionX = null,
    positionY = null,
    rotation = null,
)

private fun createRequest(
    tableNumber: String = "T1",
    locationTypeId: Long = 1L,
    assignmentPriority: Int = 0,
) = RTableDto.CreateRTableRequestDto(
    tableNumber = tableNumber,
    seats = 4,
    locationTypeId = locationTypeId,
    assignmentPriority = assignmentPriority,
)

class TableViewModelTest {
    @Test
    fun refresh_populatesTablesFromApi() =
        runTest {
            val fake = FakeRTableApiClient(listOf(table(1, "T1"), table(2, "T2")))
            val model = TableViewModel(fake)
            model.refresh()
            assertEquals(2, model.state.tables.size)
            assertEquals(listOf("T1", "T2"), model.state.tables.map { it.tableNumber })
            assertEquals(false, model.state.isLoading)
            assertNull(model.state.error)
        }

    @Test
    fun refreshFailure_yieldsNetworkError() =
        runTest {
            val failing = FakeRTableApiClient(onError = { throw NetworkApiException(RuntimeException("down")) })
            val model = TableViewModel(failing)
            model.refresh()
            assertEquals(false, model.state.isLoading)
            assertIs<UiError.Network>(model.state.error)
        }

    @Test
    fun selectLocationFilter_updatesFilterAndResetsPage() =
        runTest {
            val model = TableViewModel(FakeRTableApiClient())
            model.setPage(3)
            model.selectLocationFilter(7L)
            assertEquals(7L, model.state.selectedLocationFilter)
            assertEquals(0, model.state.currentPage)
        }

    @Test
    fun updateSearchQuery_updatesQueryAndResetsPage() =
        runTest {
            val model = TableViewModel(FakeRTableApiClient())
            model.setPage(2)
            model.updateSearchQuery("T5")
            assertEquals("T5", model.state.searchQuery)
            assertEquals(0, model.state.currentPage)
        }

    @Test
    fun setSort_sameColumnTwice_togglesAscending() =
        runTest {
            val model = TableViewModel(FakeRTableApiClient())
            assertEquals(TableSortColumn.TABLE_NUMBER, model.state.sortColumn)
            assertTrue(model.state.sortAscending)

            model.setSort(TableSortColumn.SEATS)
            assertEquals(TableSortColumn.SEATS, model.state.sortColumn)
            assertTrue(model.state.sortAscending)

            model.setSort(TableSortColumn.SEATS)
            assertEquals(false, model.state.sortAscending)
        }

    @Test
    fun addTable_createsAndReloads() =
        runTest {
            val fake = FakeRTableApiClient()
            val model = TableViewModel(fake)
            model.showAddDialog()
            model.addTable(createRequest(tableNumber = "T9"))
            assertEquals("T9", fake.created?.tableNumber)
            assertEquals(false, model.state.showAddDialog)
        }

    @Test
    fun updateTable_mapsCreateRequestToUpdateRequest_includingAssignmentPriority() =
        runTest {
            val fake = FakeRTableApiClient(listOf(table(1, "T1")))
            val model = TableViewModel(fake)
            model.refresh()
            model.showEditDialog(model.state.tables.first())
            model.updateTable(1L, createRequest(tableNumber = "T1-renamed", locationTypeId = 2L, assignmentPriority = 5))

            assertEquals(1L, fake.updatedId)
            val req = fake.updated
            assertEquals("T1-renamed", req?.tableNumber)
            assertEquals(2L, req?.locationTypeId)
            // Regression guard: the Create->Update conversion must carry assignmentPriority, or the
            // server's patch semantics (null = leave unchanged) silently discard the operator's edit.
            // composeApp's TableManagementViewModel had exactly that bug; it is fixed and pinned by
            // TableManagementViewModelPriorityTest.
            assertEquals(5, req?.assignmentPriority)
            assertEquals(false, model.state.showAddDialog)
        }

    @Test
    fun deleteTable_callsApi_andReloads() =
        runTest {
            val fake = FakeRTableApiClient(listOf(table(1, "T1")))
            val model = TableViewModel(fake)
            model.refresh()
            model.deleteTable(1L)
            assertEquals(1L, fake.deletedId)
        }

    @Test
    fun showEditDialog_loadsCombinablePartners() =
        runTest {
            val fake = FakeRTableApiClient(listOf(table(1, "T1")))
            fake.seedCombinablePartners(listOf(2L, 3L))
            val model = TableViewModel(fake)
            model.refresh()
            model.showEditDialog(model.state.tables.first())

            assertEquals(1L, fake.combinablePartnersRequestedFor)
            assertEquals(setOf(2L, 3L), model.state.combinablePartnerIds)
            assertEquals(setOf(2L, 3L), model.state.editingCombinablePartnerIds)
        }

    @Test
    fun toggleCombinablePartner_updatesEditingSetOnly() =
        runTest {
            val fake = FakeRTableApiClient(listOf(table(1, "T1")))
            fake.seedCombinablePartners(listOf(2L))
            val model = TableViewModel(fake)
            model.refresh()
            model.showEditDialog(model.state.tables.first())

            model.toggleCombinablePartner(3L)
            assertEquals(setOf(2L, 3L), model.state.editingCombinablePartnerIds)
            assertEquals(setOf(2L), model.state.combinablePartnerIds)

            model.toggleCombinablePartner(2L)
            assertEquals(setOf(3L), model.state.editingCombinablePartnerIds)
        }

    @Test
    fun updateTable_savesCombinablePartners_onlyWhenChanged() =
        runTest {
            val fake = FakeRTableApiClient(listOf(table(1, "T1")))
            fake.seedCombinablePartners(listOf(2L))
            val model = TableViewModel(fake)
            model.refresh()
            model.showEditDialog(model.state.tables.first())
            model.toggleCombinablePartner(3L)

            model.updateTable(1L, createRequest())

            assertEquals(1L to listOf(2L, 3L), fake.lastSetCombinablePartners)
        }

    @Test
    fun hideDialog_clearsEditingStateAndCombinablePartners() =
        runTest {
            val fake = FakeRTableApiClient(listOf(table(1, "T1")))
            fake.seedCombinablePartners(listOf(2L))
            val model = TableViewModel(fake)
            model.refresh()
            model.showEditDialog(model.state.tables.first())
            model.hideDialog()

            assertEquals(false, model.state.showAddDialog)
            assertNull(model.state.editingTable)
            assertEquals(emptySet(), model.state.combinablePartnerIds)
            assertEquals(emptySet(), model.state.editingCombinablePartnerIds)
        }

    @Test
    fun saveCombinablePartners_promotesTheSavedSelectionSoTheEditDialogShowsTheNewCount() =
        runTest {
            val fake = FakeRTableApiClient(listOf(table(1, "T1"), table(2, "T2"), table(3, "T3")))
            fake.seedCombinablePartners(listOf(2L))
            val model = TableViewModel(fake)
            model.refresh()
            model.loadCombinablePartners(1L)
            model.toggleCombinablePartner(3L)
            model.saveCombinablePartners(1L)
            assertEquals(setOf(2L, 3L), model.state.combinablePartnerIds)
            assertEquals(setOf(2L, 3L), model.state.editingCombinablePartnerIds)
        }

    @Test
    fun resetCombinableSelection_dropsUnsavedPicks() =
        runTest {
            val fake = FakeRTableApiClient(listOf(table(1, "T1"), table(2, "T2"), table(3, "T3")))
            fake.seedCombinablePartners(listOf(2L))
            val model = TableViewModel(fake)
            model.refresh()
            model.loadCombinablePartners(1L)
            model.toggleCombinablePartner(3L)
            model.resetCombinableSelection()
            assertEquals(setOf(2L), model.state.editingCombinablePartnerIds)
            assertEquals(setOf(2L), model.state.combinablePartnerIds)
        }

    @Test
    fun refresh_doesNotListSystemTables() =
        runTest {
            val fake = FakeRTableApiClient(listOf(table(1, "VOUCHER").copy(isSystem = true), table(2, "T2")))
            val model = TableViewModel(fake)
            model.refresh()
            assertEquals(listOf("T2"), model.state.tables.map { it.tableNumber })
        }
}
