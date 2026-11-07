package org.example.project.admin.features.dashboard.presentation.sections

/**
 * The server's "no Z-Bon has ever been closed" answer.
 *
 * `GET /api/z-bon/latest/datetime` deliberately answers 200 with this sentinel rather than 404 or
 * null (`ZBonRoutes.kt`), so a shop that has never cashed up still gets a trading-day boundary and
 * nothing downstream has to special-case a missing one. That is right for the server and wrong to
 * put in front of an operator: "Letzter Z-Bon: 01.01.2020" reads as a real close that never happened.
 * The client therefore treats it as the absence it stands for.
 *
 * **Matched WITHOUT seconds on purpose.** The route builds it as
 * `LocalDateTime.parse("2020-01-01T00:00:00").toString()`, and kotlinx-datetime's `toString()` omits
 * zero seconds — so what actually crosses the wire is `2020-01-01T00:00`. Matching the source
 * literal instead of the serialized one silently never fires, which is exactly the bug this constant
 * was added to fix. Verified against a running server, not assumed.
 */
private const val NO_ZBON_SENTINEL = "2020-01-01T00:00"

/**
 * [isEmpty] means the section renders nothing: no Z-Bon has ever been cut and no table is open, so
 * there is nothing to say about closing the day. It is deliberately independent of [failed] — a
 * failed load is not an empty one and must still render, saying it does not know.
 *
 * [hasZBon] is what the card should ask, never [lastZBonEndTime] directly: the raw field carries the
 * server's sentinel for "never closed", which is not a date worth showing anyone.
 */
data class DayCloseState(
    val isLoading: Boolean = true,
    val failed: Boolean = false,
    val lastZBonEndTime: String? = null,
    val openSessionCount: Int = 0,
) {
    val hasZBon: Boolean
        get() = lastZBonEndTime != null && !lastZBonEndTime.startsWith(NO_ZBON_SENTINEL)

    val isEmpty: Boolean get() = !hasZBon && openSessionCount == 0
}
