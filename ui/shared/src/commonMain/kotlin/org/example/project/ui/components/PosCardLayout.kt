package org.example.project.ui.components

internal data class PosCardLayout(
    val titleIndex: Int?,
    val trailingIndex: Int?,
    val detailIndices: List<Int>,
    /** [PosCardRole.Meta] cells, shown together on one wrapping line under the title. */
    val metaIndices: List<Int> = emptyList(),
)

/**
 * Resolves column roles to card slots. Exactly one title and at most one trailing cell exist; any
 * extra Title/Trailing column becomes a detail line, so **no column is ever dropped** on a phone.
 * [PosCardRole.Meta] columns (other than the title) go to the shared meta line.
 */
internal fun cardLayoutFor(roles: List<PosCardRole>): PosCardLayout {
    if (roles.isEmpty()) return PosCardLayout(null, null, emptyList())
    val title = roles.indexOf(PosCardRole.Title).takeIf { it >= 0 } ?: 0
    val trailing = roles.indices.firstOrNull { it != title && roles[it] == PosCardRole.Trailing }
    val rest = roles.indices.filter { it != title && it != trailing }
    val (meta, details) = rest.partition { roles[it] == PosCardRole.Meta }
    return PosCardLayout(title, trailing, details, meta)
}
