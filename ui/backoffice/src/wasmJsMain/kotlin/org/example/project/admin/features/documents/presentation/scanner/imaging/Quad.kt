package org.example.project.admin.features.documents.presentation.scanner.imaging

/** Four corners in reading order: top-left, top-right, bottom-right, bottom-left. */
data class Quad(
    val tl: Pt,
    val tr: Pt,
    val br: Pt,
    val bl: Pt,
) {
    fun toList(): List<Pt> = listOf(tl, tr, br, bl)

    /** Corner by index 0..3 in [toList] order. */
    operator fun get(index: Int): Pt = toList()[index]

    fun with(
        index: Int,
        p: Pt,
    ): Quad =
        when (index) {
            0 -> copy(tl = p)
            1 -> copy(tr = p)
            2 -> copy(br = p)
            3 -> copy(bl = p)
            else -> throw IllegalArgumentException("corner index $index")
        }
}
