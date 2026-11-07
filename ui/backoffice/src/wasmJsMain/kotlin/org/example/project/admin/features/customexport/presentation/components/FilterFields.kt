package org.example.project.admin.features.customexport.presentation.components

/**
 * The sections a filter query leaves behind, with the group headings retained.
 *
 * The available-fields pane is a flat list that already runs to 43 columns on Geschäftspartner and
 * grows by 6–11 at a stroke every time the schema graph reaches one more table. The group headings
 * carry that length, but they do not resolve the collisions inside it — "Erlöskonto" occurs under two
 * headings on the sales-invoice subject, and "Nettobetrag"/"Steuerbetrag"/"Bruttobetrag" under three.
 * Typing is how an operator finds a column in a list that long.
 *
 * The decisions, each one a way of getting this wrong:
 *
 * - **A ticked column is never hidden.** [chosenKeys] survives the filter regardless of the query.
 *   Unticking is done in the *chosen* pane's remove button, but the checkbox is the control an
 *   operator reaches for, and a filter that hides a ticked box hides the only affordance they know.
 *   This is why the query alone is not enough to decide a field's fate.
 * - **A matching heading keeps the whole section.** "kreditor" is how an operator asks for an *area*,
 *   not for a field whose label happens to contain it; answering with the two fields whose labels say
 *   "Kreditor" and dropping the rest of the block answers a question nobody asked.
 * - **A blank or whitespace-only query filters nothing** and returns the input unchanged — not an
 *   empty list. An empty box is the resting state of the pane, not a query that matched nothing.
 * - **Match is a case-insensitive substring of the trimmed query, and nothing more.** No token
 *   splitting, no fuzzy distance: a picker that quietly reinterprets what was typed is a picker whose
 *   empty result the operator cannot reason about.
 *
 * Matching is on the field's *label* — never its key, which is the server's `table.column` encoding
 * and would make "partner" match forty rows that do not say "Partner" anywhere on screen.
 *
 * Pure and free of Compose on purpose, exactly as [groupFields] and [lockedAggregateKeys] are: the
 * decisions are the part that has to be right, and the wasm test leg can assert on a list where it
 * cannot assert on a canvas.
 *
 * @param sections the picker's sections, already arranged by [groupFields].
 * @param query what the operator typed. Blank means no filtering.
 * @param chosenKeys the field keys currently ticked; these stay visible whatever the query.
 * @return the surviving sections, in the input's order, each with its heading unchanged.
 */
fun filterFields(
    sections: List<FieldSection>,
    query: String,
    chosenKeys: Set<String>,
): List<FieldSection> {
    val needle = query.trim()
    if (needle.isEmpty()) return sections

    return sections.mapNotNull { section ->
        if (section.heading?.contains(needle, ignoreCase = true) == true) return@mapNotNull section
        val kept = section.fields.filter { it.label.contains(needle, ignoreCase = true) || it.key in chosenKeys }
        if (kept.isEmpty()) null else FieldSection(section.heading, kept)
    }
}
