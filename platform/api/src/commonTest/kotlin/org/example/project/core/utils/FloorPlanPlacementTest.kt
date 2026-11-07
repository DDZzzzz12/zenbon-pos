package org.example.project.core.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class FloorPlanPlacementTest {
    private val cells = GridCellDimensions(cellWidth = 15f, cellHeight = 12f)

    @Test
    fun rectangularCellsScaleWidthAndHeightIndependently() {
        val bounds = FloorPlanPlacement.bounds(gridX = 10, gridY = 5, wCells = 4, hCells = 6, cells = cells)
        assertEquals(150f, bounds.x)
        assertEquals(60f, bounds.y)
        assertEquals(60f, bounds.width)
        assertEquals(72f, bounds.height)
    }

    @Test
    fun squareFromWidthMatchesTheTillsCircularTableRule() {
        // The till sizes a circular table from cell WIDTH only, so it stays a circle on a
        // viewport whose cells are wider than they are tall. Height must follow width, not cellHeight.
        val bounds =
            FloorPlanPlacement.bounds(
                gridX = 0,
                gridY = 0,
                wCells = 4,
                hCells = 4,
                cells = cells,
                squareFromWidth = true,
            )
        assertEquals(60f, bounds.width)
        assertEquals(60f, bounds.height)
    }

    @Test
    fun squareFromWidthFollowsTheClampedWidthNotTheRequestedSpan() {
        // A span wider than the grid is capped at its 80 columns, and the square follows that capped
        // width, not the requested 100. Pinning this stops the square being derived from the raw span.
        val bounds =
            FloorPlanPlacement.bounds(
                gridX = 0,
                gridY = 0,
                wCells = 100,
                hCells = 100,
                cells = cells,
                squareFromWidth = true,
            )
        assertEquals(80f * 15f, bounds.width)
        assertEquals(80f * 15f, bounds.height)
    }

    @Test
    fun anUnmeasuredViewportYieldsAnEmptyRectRatherThanNaN() {
        // calculateCellDimensions returns 0x0 cells before the canvas has been measured.
        val bounds =
            FloorPlanPlacement.bounds(
                gridX = 10,
                gridY = 5,
                wCells = 4,
                hCells = 6,
                cells = GridCellDimensions(cellWidth = 0f, cellHeight = 0f),
            )
        assertEquals(0f, bounds.x)
        assertEquals(0f, bounds.y)
        assertEquals(0f, bounds.width)
        assertEquals(0f, bounds.height)
    }

    @Test
    fun positionBeyondTheGridMovesInwardAndKeepsItsSize() {
        // UI-E1-13: a table stored past the last column used to be pinned to column 79 and shrunk to
        // the one remaining cell, an invisible sliver. It now keeps its 4 cells and ends at the edge.
        val bounds = FloorPlanPlacement.bounds(gridX = 200, gridY = 90, wCells = 4, hCells = 6, cells = cells)
        assertEquals(76f * 15f, bounds.x)
        assertEquals(60f, bounds.width)
        assertEquals(49f * 12f, bounds.y)
        assertEquals(72f, bounds.height)
    }

    @Test
    fun aTableSpanIsFlooredAtTwoCellsAndDefaultsToTwo() {
        assertEquals(2, FloorPlanPlacement.tableSpan(null))
        assertEquals(2, FloorPlanPlacement.tableSpan(0))
        assertEquals(2, FloorPlanPlacement.tableSpan(1))
        assertEquals(7, FloorPlanPlacement.tableSpan(7))
    }

    @Test
    fun anItemOverlappingTheLastColumnIsPulledBackOntoTheGrid() {
        val bounds = FloorPlanPlacement.bounds(gridX = 78, gridY = 0, wCells = 4, hCells = 4, cells = cells)
        assertEquals(76f * 15f, bounds.x)
        assertEquals(60f, bounds.width)
    }

    @Test
    fun negativePositionIsClampedToTheOrigin() {
        val bounds = FloorPlanPlacement.bounds(gridX = -5, gridY = -3, wCells = 2, hCells = 2, cells = cells)
        assertEquals(0f, bounds.x)
        assertEquals(0f, bounds.y)
    }

    @Test
    fun zeroOrNegativeSpanIsTreatedAsOneCell() {
        val bounds = FloorPlanPlacement.bounds(gridX = 0, gridY = 0, wCells = 0, hCells = -2, cells = cells)
        assertEquals(15f, bounds.width)
        assertEquals(12f, bounds.height)
    }
}
