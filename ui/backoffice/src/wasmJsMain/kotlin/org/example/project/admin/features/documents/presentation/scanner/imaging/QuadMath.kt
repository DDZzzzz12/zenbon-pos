package org.example.project.admin.features.documents.presentation.scanner.imaging

import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Pure geometry for the scanner. No DOM, no Compose: everything here is unit-tested. */
object QuadMath {
    /** Orders four points clockwise starting from the top-left (smallest x + y). Robust for diamonds. */
    fun order(points: List<Pt>): Quad {
        require(points.size == 4) { "a quad needs exactly 4 points, got ${points.size}" }
        val cx = points.sumOf { it.x.toDouble() }.toFloat() / 4f
        val cy = points.sumOf { it.y.toDouble() }.toFloat() / 4f
        // Screen y points down, so ascending atan2 runs clockwise on screen.
        val clockwise = points.sortedBy { atan2(it.y - cy, it.x - cx) }
        val start = clockwise.indices.minBy { clockwise[it].x + clockwise[it].y }
        val r = clockwise.drop(start) + clockwise.take(start)
        return Quad(r[0], r[1], r[2], r[3])
    }

    fun area(q: Quad): Float {
        val p = q.toList()
        var sum = 0f
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            sum += a.x * b.y - b.x * a.y
        }
        return kotlin.math.abs(sum) / 2f
    }

    fun distance(
        a: Pt,
        b: Pt,
    ): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    /** True when every turn has the same direction (no folds, no bow-ties). */
    fun isConvex(q: Quad): Boolean {
        val p = q.toList()
        var sign = 0
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            val c = p[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (cross == 0f) return false
            val s = if (cross > 0f) 1 else -1
            if (sign == 0) {
                sign = s
            } else if (sign != s) {
                return false
            }
        }
        return true
    }

    private fun minSeparationHolds(
        q: Quad,
        minSeparation: Float,
    ): Boolean {
        val p = q.toList()
        for (i in 0 until 4) {
            for (j in i + 1 until 4) {
                if (distance(p[i], p[j]) < minSeparation) return false
            }
        }
        return true
    }

    fun isValid(
        q: Quad,
        minSeparation: Float,
    ): Boolean = isConvex(q) && minSeparationHolds(q, minSeparation) && area(q) >= 4f * minSeparation * minSeparation

    /**
     * Moves corner [index] toward [target], clamped to the image. An invalid result is not applied;
     * instead the move slides along x, then along y, so a drag that hits a limit keeps tracking the
     * finger on the free axis rather than freezing. Returns [q] unchanged if nothing is valid.
     */
    fun moveCorner(
        q: Quad,
        index: Int,
        target: Pt,
        width: Float,
        height: Float,
        minSeparation: Float,
    ): Quad {
        val clamped = Pt(target.x.coerceIn(0f, width), target.y.coerceIn(0f, height))
        val current = q[index]
        val attempts = listOf(clamped, Pt(clamped.x, current.y), Pt(current.x, clamped.y))
        for (attempt in attempts) {
            val candidate = q.with(index, attempt)
            if (isValid(candidate, minSeparation)) return candidate
        }
        return q
    }

    /** Index of the corner within [radius] of [p], nearest first; null when none is close enough. */
    fun nearestCorner(
        q: Quad,
        p: Pt,
        radius: Float,
    ): Int? {
        val best = (0..3).minBy { distance(q[it], p) }
        return if (distance(q[best], p) <= radius) best else null
    }

    fun fit(
        imageWidth: Float,
        imageHeight: Float,
        viewWidth: Float,
        viewHeight: Float,
    ): Fit {
        if (imageWidth <= 0f || imageHeight <= 0f || viewWidth <= 0f || viewHeight <= 0f) return Fit(1f, 0f, 0f)
        val s = min(viewWidth / imageWidth, viewHeight / imageHeight)
        return Fit(s, (viewWidth - imageWidth * s) / 2f, (viewHeight - imageHeight * s) / 2f)
    }

    fun toView(
        p: Pt,
        fit: Fit,
    ): Pt = Pt(p.x * fit.scale + fit.offsetX, p.y * fit.scale + fit.offsetY)

    fun toImage(
        p: Pt,
        fit: Fit,
    ): Pt = Pt((p.x - fit.offsetX) / fit.scale, (p.y - fit.offsetY) / fit.scale)

    fun scale(
        q: Quad,
        sx: Float,
        sy: Float,
    ): Quad = Quad(q.tl.scaled(sx, sy), q.tr.scaled(sx, sy), q.br.scaled(sx, sy), q.bl.scaled(sx, sy))

    private fun Pt.scaled(
        sx: Float,
        sy: Float,
    ): Pt = Pt(x * sx, y * sy)

    /** Output rectangle for the warp: the longer of each pair of opposite edges, capped on the long side. */
    fun outputSize(
        q: Quad,
        maxLongEdge: Int,
    ): Pair<Int, Int> {
        var w = max(distance(q.tl, q.tr), distance(q.bl, q.br))
        var h = max(distance(q.tl, q.bl), distance(q.tr, q.br))
        val longest = max(w, h)
        if (longest > maxLongEdge) {
            val k = maxLongEdge / longest
            w *= k
            h *= k
        }
        return max(1, w.roundToInt()) to max(1, h.roundToInt())
    }

    /** Whole-image proposal, slightly inset so every handle is reachable. */
    fun insetFullImage(
        width: Float,
        height: Float,
        fraction: Float = 0.04f,
    ): Quad {
        val dx = width * fraction
        val dy = height * fraction
        return Quad(Pt(dx, dy), Pt(width - dx, dy), Pt(width - dx, height - dy), Pt(dx, height - dy))
    }

    /** Whole image with no inset: the "use whole image" button. */
    fun fullImage(
        width: Float,
        height: Float,
    ): Quad = Quad(Pt(0f, 0f), Pt(width, 0f), Pt(width, height), Pt(0f, height))
}
