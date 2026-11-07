package org.example.project.features.reports.presentation.viewmodel

/**
 * Appends one page of a paged list to the rows already shown, dropping rows whose [key] is already
 * present.
 *
 * The statistics endpoints page by `OFFSET page × size`. When a bill closes between two page loads,
 * every later row shifts down by one, so the next page repeats the last row of the previous one. The
 * lists render with `LazyColumn(key = …)`, and a repeated key throws "Key … was already used" — a
 * crash of the till's dashboard during service. The first occurrence wins, so a row already on
 * screen keeps its position.
 */
fun <T, K> List<T>.appendPage(
    page: List<T>,
    key: (T) -> K,
): List<T> {
    val seen = mapTo(HashSet(), key)
    return this + page.filter { seen.add(key(it)) }
}
