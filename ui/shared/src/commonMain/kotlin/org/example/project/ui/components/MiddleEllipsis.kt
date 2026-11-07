package org.example.project.ui.components

/** The ellipsis [middleEllipsize] puts where it removes characters. */
const val MIDDLE_ELLIPSIS = "…"

/**
 * [text] shortened to fit [maxWidth] by removing characters from its **middle**, so both the start
 * and the distinguishing end stay visible ("Rechnung_2026-…_RG-20261016-0007.pdf"). Returns [text]
 * unchanged when it fits. [measure] gives the rendered width of a candidate in the same unit as
 * [maxWidth]. Binary search over how many characters survive, so a long name costs ~log2(n) measures.
 *
 * Exists because `TextOverflow.MiddleEllipsis` renders as an END ellipsis on Compose for Web (Skia's
 * paragraph only ellipsizes at the end), which hid exactly the part that tells two files apart (NEW-05).
 */
fun middleEllipsize(
    text: String,
    maxWidth: Int,
    measure: (String) -> Int,
): String {
    if (text.isEmpty() || measure(text) <= maxWidth) return text
    var low = 0
    var high = text.length - 1
    var best = MIDDLE_ELLIPSIS
    while (low <= high) {
        val kept = (low + high) / 2
        val candidate = keepEnds(text, kept)
        if (measure(candidate) <= maxWidth) {
            best = candidate
            low = kept + 1
        } else {
            high = kept - 1
        }
    }
    return best
}

/** The first `ceil(kept / 2)` and the last `floor(kept / 2)` characters around the ellipsis. */
private fun keepEnds(
    text: String,
    kept: Int,
): String {
    val head = (kept + 1) / 2
    val tail = kept / 2
    return text.take(head) + MIDDLE_ELLIPSIS + text.takeLast(tail)
}
