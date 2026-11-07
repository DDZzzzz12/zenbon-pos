package org.example.project.admin.features.floorplan

import kotlinx.coroutines.test.runTest
import org.example.project.admin.features.floorplan.presentation.FloorPlanEditorViewModel
import org.example.project.admin.features.floorplan.presentation.FloorPlanSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FloorPlanEditorViewModelTest {
    private fun viewModel(
        tables: List<org.example.project.core.domain.dto.table.RTableDto.RTableResponseDto> = emptyList(),
        elements: List<org.example.project.core.domain.dto.table.RoomElementDto.RoomElementResponseDto> = emptyList(),
    ): Triple<FloorPlanEditorViewModel, FakeFloorPlanRTableApiClient, FakeFloorPlanRoomElementApiClient> {
        val tableApi = FakeFloorPlanRTableApiClient(tables)
        val elementApi = FakeFloorPlanRoomElementApiClient(elements)
        return Triple(FloorPlanEditorViewModel(tableApi, elementApi), tableApi, elementApi)
    }

    @Test
    fun loadPopulatesTablesAndElementsForTheLocation() =
        runTest {
            // Include one table + element at a *different* location to prove filtering by locationTypeId.
            val (model, _, _) =
                viewModel(
                    tables = listOf(table(1, 2, 3, locationTypeId = 1L), table(2, 4, 5, locationTypeId = 2L)),
                    elements = listOf(element(10, 6, 7, locationTypeId = 1L), element(11, 8, 9, locationTypeId = 2L)),
                )
            model.load(1L)
            assertEquals(1, model.state.tables.size)
            assertEquals(
                1L,
                model.state.tables
                    .single()
                    .id,
            )
            assertEquals(1, model.state.elements.size)
            assertEquals(
                10L,
                model.state.elements
                    .single()
                    .id,
            )
            assertFalse(model.state.hasUnsavedChanges)
            assertEquals(false, model.state.isLoading)
        }

    @Test
    fun movingATableUpdatesItsGridPositionAndMarksItDirty() =
        runTest {
            val (model, _, _) = viewModel(tables = listOf(table(1, 2, 3)))
            model.load(1L)

            model.moveTable(1L, 8, 9)

            val moved = model.state.tables.single { it.id == 1L }
            assertEquals(8, moved.gridX)
            assertEquals(9, moved.gridY)
            assertTrue(model.state.hasUnsavedChanges)
        }

    @Test
    fun saveWritesOnlyDirtyTablesInOneBatchThenClearsDirty() =
        runTest {
            val (model, tableApi, _) = viewModel(tables = listOf(table(1, 2, 3), table(2, 4, 5)))
            model.load(1L)

            model.moveTable(1L, 8, 9) // only table 1 is dirty
            model.save()

            assertEquals(1, tableApi.batchCalls.size)
            assertEquals(
                listOf(1L),
                tableApi.batchCalls
                    .single()
                    .updates
                    .map { it.tableId },
            )
            assertFalse(model.state.hasUnsavedChanges)
        }

    @Test
    fun deletingASelectedTableRemovesItAndReloads() =
        runTest {
            val (model, tableApi, _) = viewModel(tables = listOf(table(1, 2, 3)))
            model.load(1L)

            model.select(FloorPlanSelection.Table(1L))
            model.deleteSelected()

            assertEquals(listOf(1L), tableApi.deleted)
        }

    @Test
    fun movingAnElementMarksItDirtyAndSaveWritesItsPosition() =
        runTest {
            val (model, _, elementApi) = viewModel(elements = listOf(element(10, 6, 7)))
            model.load(1L)

            model.moveElement(10L, 7, 8)
            assertTrue(model.state.hasUnsavedChanges)

            model.save()

            assertEquals(1, elementApi.positionWrites.size)
            val (id, request) = elementApi.positionWrites.single()
            assertEquals(10L, id)
            assertEquals(7f, request.positionX)
            assertEquals(8f, request.positionY)
        }

    @Test
    fun resizingATableMarksDirtyAndSaveSendsNewWidthHeight() =
        runTest {
            val (model, tableApi, _) = viewModel(tables = listOf(table(1, 2, 3)))
            model.load(1L)

            model.resizeTable(1L, 6, 4)

            val resized = model.state.tables.single { it.id == 1L }
            assertEquals(6, resized.gridWidth)
            assertEquals(4, resized.gridHeight)
            assertTrue(model.state.hasUnsavedChanges)

            model.save()

            val update =
                tableApi.batchCalls
                    .single()
                    .updates
                    .single { it.tableId == 1L }
            assertEquals(6f, update.width)
            assertEquals(4f, update.height)
        }

    @Test
    fun rotatingATableSetsRotationAndSaveSendsIt() =
        runTest {
            val (model, tableApi, _) = viewModel(tables = listOf(table(1, 2, 3)))
            model.load(1L)

            model.rotateTable(1L, 90f)

            assertEquals(
                90f,
                model.state.tables
                    .single { it.id == 1L }
                    .rotation,
            )

            model.save()

            val update =
                tableApi.batchCalls
                    .single()
                    .updates
                    .single { it.tableId == 1L }
            assertEquals(90f, update.rotation)
        }

    @Test
    fun resizingAnElementCallsUpdateDimensions() =
        runTest {
            val (model, _, elementApi) = viewModel(elements = listOf(element(10, 6, 7)))
            model.load(1L)

            model.resizeElement(10L, 5, 2)

            val resized = model.state.elements.single { it.id == 10L }
            assertEquals(5, resized.gridWidth)
            assertEquals(2, resized.gridHeight)
            assertTrue(model.state.hasUnsavedChanges)

            model.save()

            val (id, request) = elementApi.dimensionWrites.single()
            assertEquals(10L, id)
            assertEquals(5f, request.width)
            assertEquals(2f, request.height)
        }

    @Test
    fun discardDraftClearsTheDirtyMarksAndSelection() =
        runTest {
            val (model, _, _) = viewModel(tables = listOf(table(1, 2, 3)))
            model.load(1L)
            model.moveTable(1L, 8, 9)
            model.select(FloorPlanSelection.Table(1L))
            assertTrue(model.state.hasUnsavedChanges)

            model.discardDraft()

            assertFalse(model.state.hasUnsavedChanges)
            assertEquals(null, model.state.selection)
        }

    @Test
    fun updateTableNumberAndSeats_sendsOnlyThoseTwoFieldsAndReloads() =
        runTest {
            val (model, tableApi, _) = viewModel(tables = listOf(table(1, 2, 3)))
            model.load(1L)

            model.updateTableNumberAndSeats(1L, "12", 6)

            val (id, request) = tableApi.updates.single()
            assertEquals(1L, id)
            assertEquals("12", request.tableNumber)
            assertEquals(6, request.seats)
            assertEquals(null, request.positionX)
            assertEquals(null, request.locationTypeId)
            assertFalse(model.state.isLoading)
        }
}
