package org.example.project.features.zBon.domain

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.setup.ShopInfoDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.kassenbuch.domain.models.CashBook
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.setup.domain.service.ShopIdentityChangeGuard
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.TestOnlyApi
import org.slf4j.LoggerFactory

/**
 * DSFinV-K Tz. 3.2 closing duty for shop-info edits (audit KABS-2, round-6 C5): refuses a
 * fiscal-identity change while business is open and freezes the pre-edit Stammdaten onto closings
 * that carry no snapshot. Extracted verbatim from `ShopInfoServiceImpl.updateShopInfo`.
 */
class FiscalShopIdentityGuard : ShopIdentityChangeGuard {
    private val logger = LoggerFactory.getLogger(FiscalShopIdentityGuard::class.java)

    @OptIn(TestOnlyApi::class)
    override fun beforeUpdate(
        current: ShopInfo,
        request: ShopInfoDto.UpdateShopInfoRequestDto,
    ) {
        val shopId = current.id.value
        // DSFinV-K Tz. 3.2 (p.23) closing duty, audit KABS-2: „Werden Änderungen an den …
        // Stammdaten vorgenommen, ist zuvor automatisch ein Abschluss zu erstellen." Turnover
        // already recorded must be closed under the identity that applied to it before that
        // identity changes. The per-closing snapshot fixes already-closed periods; this guard
        // covers the still-open one.
        //
        // Narrow on purpose, in both directions:
        //  * only DSFinV-K-relevant fields count — a logo, timezone or bank-detail edit
        //    carries no fiscal identity and must not demand a Kassenabschluss;
        //  * only fields whose value actually CHANGES count — resubmitting the same address
        //    is not a Stammdaten change.
        val fiscalChange = changesFiscalIdentity(current, request)
        if (fiscalChange && OpenBusinessCheck.hasOpenBusiness(CashScope.ALL_BOOKS)) {
            throw closingRequired(OpenBusinessCheck.blockingBook())
        }

        // Freeze-on-edit — round-6 C5. Closings taken before the snapshot existed carry NULL
        // Stammdaten and render through the live-row fallback, so a fiscal edit would silently
        // re-attribute their turnover to the new identity. This is the LAST moment at which the
        // old values are still knowable, so record them now, on the closings that lack a
        // snapshot only — never over one that is already present.
        //
        // Deliberately BEFORE repository.update: `current` still holds the pre-edit values, and
        // those are the ones under which those closings were exported. Same reason this is not
        // gated on the backfill having run — on the setup-wizard install path it never does
        // (the wizard writes no SHOP_INFO entry_log row for V95's precondition to find).
        //
        // Narrow on the same predicate as the closing duty above: a logo or timezone edit
        // changes no exported value, so there is nothing to preserve and the closing keeps its
        // honest NULL.
        if (fiscalChange) {
            val frozen = StammdatenSnapshot.freezeUnsnapshottedClosings(current)
            if (frozen > 0) {
                logger.info(
                    "updateShopInfo: froze pre-edit Stammdaten onto {} un-snapshotted closing(s) of shopId={}",
                    frozen,
                    shopId,
                )
            }
        }
    }

    /**
     * `true` when [request] would change at least one field that appears in the DSFinV-K
     * Stammdaten (`cashpointclosing.csv` / `location.csv`): name, street, PLZ, city, country,
     * Steuernummer, USt-IdNr.
     *
     * A field left `null` in the partial-update DTO is "not being changed", and a field submitted
     * with its current value changes nothing — neither triggers the closing duty. Everything not
     * listed here (logo, timezone, website, phone, email, bank details, company name, restaurant /
     * kiosk mode, TSE mode) is invisible to the export and is deliberately excluded.
     *
     * `ustIdNr` is included: it is exported as DSFinV-K `USTID` and snapshotted onto every Z-Bon,
     * so changing it is a Stammdaten change and owes the same closing duty as a changed
     * Steuernummer. It became editable when the field was added to the shop-info DTOs.
     */
    @TestOnlyApi
    fun changesFiscalIdentity(
        current: ShopInfo,
        request: ShopInfoDto.UpdateShopInfoRequestDto,
    ): Boolean {
        fun changed(
            new: Any?,
            old: Any?,
        ) = new != null && new != old
        return changed(request.shopName, current.shopName) ||
            changed(request.shopAddress, current.shopAddress) ||
            changed(request.shopCity, current.shopCity) ||
            changed(request.zipCode, current.zipCode) ||
            changed(request.shopCountry, current.shopCountry) ||
            changed(request.shopTaxNumber, current.shopTaxNumber) ||
            changed(request.ustIdNr, current.ustIdNr)
    }

    /**
     * Names the book AND the closing that clears it (T9-11). A Z-Bon locks only the fiscal
     * Ladenkasse; a Tresor or other Nebenkasse is locked by its own Kassensturz. Telling the
     * operator to "create a Z-Bon" for a blocking Tresor sent them to an action that cannot help.
     */
    private fun closingRequired(blockingBook: CashBook?): PosException {
        val remedy =
            if (blockingBook == null || blockingBook.isFiscal) {
                "Create a Kassenabschluss (Z-Bon) first"
            } else {
                "Take a Kassensturz of '${blockingBook.name}' first (a Z-Bon does not close it)"
            }
        return PosException(
            PosErrorCode.SHOP_INFO_CLOSING_REQUIRED,
            HttpStatusCode.Conflict,
            "Fiscally relevant shop master data cannot be changed while cash book " +
                "'${blockingBook?.name ?: "?"}' has an unclosed period. $remedy — DSFinV-K Tz. 3.2.",
        )
    }
}
