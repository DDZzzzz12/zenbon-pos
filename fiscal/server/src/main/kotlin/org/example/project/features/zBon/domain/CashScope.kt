package org.example.project.features.zBon.domain

/**
 * Which cash books a "is there open business?" question covers.
 *
 * The two values exist because [OpenBusinessCheck]'s two callers have OPPOSITE polarity and,
 * since cash books were introduced, genuinely different scope:
 *
 *  * [FISCAL_ONLY] — `ZBonServiceImpl.createReport` refuses a closing when NOTHING is open. A
 *    Tresor entry is not business a Z-Bon can close, so counting it would permit an operator to
 *    create an EMPTY closing with no register activity.
 *  * [ALL_BOOKS] — `ShopInfoServiceImpl.updateShopInfo` refuses a Stammdaten edit while something
 *    IS open. Safe cash is still recorded under the shop's fiscal identity in force when it was
 *    booked (DSFinV-K Tz. 3.2, round-7 C9), so an unclosed Tresor period must still block the edit.
 *
 * This is a parameter, deliberately, and NOT two copies of the predicate — [OpenBusinessCheck]
 * warns that two copies would drift apart. The parameter is required, with no default: a default
 * would let a future caller silently get the wrong scope on a fiscal path.
 */
enum class CashScope {
    FISCAL_ONLY,
    ALL_BOOKS,
}
