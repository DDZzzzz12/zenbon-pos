package org.example.project.admin.features.documents.presentation.scanner.imaging

/**
 * Decides when the live outline has been still long enough to trust. The anchor is the quad at the
 * moment the clock last restarted; movement is measured against it, not frame to frame, so a slow
 * drift cannot creep past the tolerance one small step at a time.
 */
class StabilityTracker(
    private val toleranceFraction: Float = 0.01f,
    private val requiredMillis: Long = 700,
) {
    private var anchor: Quad? = null
    private var since: Long = 0

    fun update(
        quad: Quad?,
        frameDiagonal: Float,
        nowMillis: Long,
    ): Boolean {
        if (quad == null) {
            reset()
            return false
        }
        val held = anchor
        val tolerance = frameDiagonal * toleranceFraction
        if (held == null || maxCornerDistance(held, quad) > tolerance) {
            anchor = quad
            since = nowMillis
            return false
        }
        return nowMillis - since >= requiredMillis
    }

    fun reset() {
        anchor = null
    }

    private fun maxCornerDistance(
        a: Quad,
        b: Quad,
    ): Float = (0..3).maxOf { QuadMath.distance(a[it], b[it]) }
}
