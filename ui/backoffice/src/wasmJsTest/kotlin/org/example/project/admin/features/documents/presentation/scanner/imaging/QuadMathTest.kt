package org.example.project.admin.features.documents.presentation.scanner.imaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuadMathTest {
    private val upright = Quad(Pt(10f, 10f), Pt(110f, 10f), Pt(110f, 160f), Pt(10f, 160f))

    @Test
    fun orderIsIndependentOfInputRotation() {
        val corners = listOf(upright.tl, upright.tr, upright.br, upright.bl)
        for (shift in 0..3) {
            val rotated = corners.drop(shift) + corners.take(shift)
            assertEquals(upright, QuadMath.order(rotated), "shift $shift")
        }
    }

    @Test
    fun orderHandlesAReversedWinding() {
        val reversed = listOf(upright.tl, upright.bl, upright.br, upright.tr)
        assertEquals(upright, QuadMath.order(reversed))
    }

    @Test
    fun orderHandlesADiamondWithoutDuplicatingACorner() {
        val ordered = QuadMath.order(listOf(Pt(0f, 1f), Pt(1f, 0f), Pt(2f, 1f), Pt(1f, 2f)))
        assertEquals(4, ordered.toList().toSet().size)
    }

    @Test
    fun areaOfARectangle() {
        assertEquals(15000f, QuadMath.area(upright))
    }

    @Test
    fun uprightRectangleIsConvex() {
        assertTrue(QuadMath.isConvex(upright))
    }

    @Test
    fun bowTieIsNotConvex() {
        val bowTie = Quad(Pt(0f, 0f), Pt(100f, 100f), Pt(100f, 0f), Pt(0f, 100f))
        assertFalse(QuadMath.isConvex(bowTie))
    }

    @Test
    fun dentedQuadIsNotConvex() {
        val dent = Quad(Pt(0f, 0f), Pt(100f, 0f), Pt(20f, 20f), Pt(0f, 100f))
        assertFalse(QuadMath.isConvex(dent))
    }

    @Test
    fun moveCornerClampsToTheImage() {
        val moved = QuadMath.moveCorner(upright, 0, Pt(-50f, -50f), 200f, 200f, 5f)
        assertEquals(Pt(0f, 0f), moved.tl)
    }

    @Test
    fun moveCornerRejectsAMoveThatMakesTheQuadNonConvex() {
        // Dragging top-left far past the bottom-right would fold the quad.
        val moved = QuadMath.moveCorner(upright, 0, Pt(150f, 190f), 200f, 200f, 5f)
        assertEquals(upright, moved)
    }

    @Test
    fun moveCornerSlidesAlongAnAxisWhenTheFullMoveIsInvalid() {
        // Target x is fine, target y would fold the quad: x alone is applied.
        val moved = QuadMath.moveCorner(upright, 0, Pt(60f, 190f), 200f, 200f, 5f)
        assertEquals(Pt(60f, 10f), moved.tl)
    }

    @Test
    fun moveCornerKeepsMinimumSeparation() {
        val moved = QuadMath.moveCorner(upright, 0, Pt(108f, 10f), 200f, 200f, 5f)
        assertEquals(upright, moved)
    }

    @Test
    fun nearestCornerFindsOneWithinRadius() {
        assertEquals(2, QuadMath.nearestCorner(upright, Pt(112f, 158f), 20f))
        assertNull(QuadMath.nearestCorner(upright, Pt(60f, 80f), 20f))
    }

    @Test
    fun fitMapsBothWaysAndRoundTrips() {
        val fit = QuadMath.fit(imageWidth = 400f, imageHeight = 200f, viewWidth = 200f, viewHeight = 200f)
        assertEquals(0.5f, fit.scale)
        assertEquals(0f, fit.offsetX)
        assertEquals(50f, fit.offsetY)
        val p = Pt(100f, 40f)
        assertEquals(p, QuadMath.toImage(QuadMath.toView(p, fit), fit))
        assertEquals(Pt(50f, 70f), QuadMath.toView(p, fit))
    }

    @Test
    fun fitIsAnIdentityForNonPositiveSizes() {
        val identity = Fit(1f, 0f, 0f)
        assertEquals(identity, QuadMath.fit(0f, 200f, 100f, 100f))
        assertEquals(identity, QuadMath.fit(200f, 0f, 100f, 100f))
        assertEquals(identity, QuadMath.fit(200f, 200f, 0f, 100f))
        assertEquals(identity, QuadMath.fit(200f, 200f, 100f, -1f))
    }

    @Test
    fun aTinyButConvexQuadIsNotValid() {
        // Corners are 12 apart (separation holds) but the area (144) is below 4 * 12 * 12.
        val tiny = Quad(Pt(0f, 0f), Pt(12f, 0f), Pt(12f, 12f), Pt(0f, 12f))
        assertTrue(QuadMath.isConvex(tiny))
        assertFalse(QuadMath.isValid(tiny, 12f))
        assertTrue(QuadMath.isValid(upright, 12f))
    }

    @Test
    fun outputSizeUsesTheLongerOppositeEdges() {
        val skew = Quad(Pt(0f, 0f), Pt(100f, 0f), Pt(120f, 200f), Pt(0f, 190f))
        val (w, h) = QuadMath.outputSize(skew, maxLongEdge = 10_000)
        assertTrue(w in 100..120, "w=$w")
        assertTrue(h in 190..201, "h=$h")
    }

    @Test
    fun outputSizeIsScaledDownToTheLongEdgeCap() {
        val (w, h) = QuadMath.outputSize(upright, maxLongEdge = 75)
        assertEquals(75, h)
        assertEquals(50, w)
    }

    @Test
    fun insetFullImageIsValidAndInsideTheImage() {
        val q = QuadMath.insetFullImage(300f, 400f)
        assertTrue(QuadMath.isConvex(q))
        assertTrue(q.toList().all { it.x in 0f..300f && it.y in 0f..400f })
        assertNotEquals(Pt(0f, 0f), q.tl)
    }

    @Test
    fun scaleMultipliesEveryCorner() {
        assertEquals(Pt(20f, 30f), QuadMath.scale(upright, 2f, 3f).tl)
    }
}
