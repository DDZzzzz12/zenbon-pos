package org.example.project.admin.features.zBon.closing

import org.example.project.core.domain.enums.auth.Capability

/** What the adminApp offers around closing the day — the client half of `ZBonRules`/`ZBonServiceImpl`. */
data class ZBonClosingAccess(
    /** `POST /api/z-bon` needs `ZBON_CLOSE`. */
    val mayClose: Boolean,
    /** In-closing tip payouts and the top-up need `CASHBOOK_WRITE`. */
    val paysOutTips: Boolean,
    /** The new Z-Bon can be opened in the list and detail with `ZBON_MANAGE`. */
    val opensZBon: Boolean,
) {
    companion object {
        fun from(holds: (Capability) -> Boolean) =
            ZBonClosingAccess(
                mayClose = holds(Capability.ZBON_CLOSE),
                paysOutTips = holds(Capability.CASHBOOK_WRITE),
                opensZBon = holds(Capability.ZBON_MANAGE),
            )
    }
}
