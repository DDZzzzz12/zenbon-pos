package org.example.project.ui.components.filter

import androidx.compose.runtime.Composable
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.str

/**
 * The count in a [PosFilterBar] result line: "50+" while further pages exist, so a page size never
 * reads as the total; otherwise the plain number.
 */
fun filterResultCount(
    count: Int,
    hasMore: Boolean,
): String = if (hasMore) "$count+" else count.toString()

/**
 * The key of a result line whose noun changes with the count ("1 Tisch", "2 Tische"): [one] only for
 * an exact count of 1, [other] for 0, any larger count and any lower bound ("1+" means more exist).
 * Languages without a plural (Chinese) give both keys the same text.
 */
fun pluralResultKey(
    count: Long,
    one: StringKey,
    other: StringKey,
    hasMore: Boolean = false,
): StringKey = if (count == 1L && !hasMore) one else other

/** [pluralResultKey] for an `Int` count. */
fun pluralResultKey(
    count: Int,
    one: StringKey,
    other: StringKey,
    hasMore: Boolean = false,
): StringKey = pluralResultKey(count.toLong(), one, other, hasMore)

/** The whole result line for [count]: the plural-aware text with [filterResultCount]'s "50+" figure. */
@Composable
fun filterResultLine(
    count: Int,
    one: StringKey,
    other: StringKey,
    hasMore: Boolean = false,
): String = str(pluralResultKey(count, one, other, hasMore), filterResultCount(count, hasMore))
