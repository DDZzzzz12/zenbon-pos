package org.example.project.utils

import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.features.bill.domain.models.Bill
import java.math.BigDecimal

/**
 * How much of a bill's tip must be carried OUTSIDE `bill.vatDetails`.
 *
 * It has THREE consumers today. **Signing**: `ProcessDataBuilder.buildForBill` and the storno call
 * site in `BillServiceImpl.deleteBill` pass this amount as `nonTaxableExtra`, which lands it in the
 * 5th ("nicht steuerbar") brutto slot. **The DSFinV-K export**:
 * `DsfinvkExportServiceImpl.generateBonkopfUst` (Task P1.2) emits the matching `transactions_vat`
 * key-5 row from it. For a storno BOTH the signing site and the export negate
 * [outsideVatDetails] of the ORIGINAL — one formula, so they cannot drift. **The Z-Bon drawer
 * derivation**: `DrawerCashDerivation.derive` (Task P1.3) adds this back to `netCashAmount` over the
 * SALE bills, because the tip is tendered and sits in the drawer while no longer being part of
 * `revenueGross`; `ZBonServiceImpl` then books it as its own "TE:Trinkgeld (AN)" Kassenbuch line, in
 * place of the "TE0%" line the removed VAT row used to produce. All three consumers are live.
 *
 * Only an EMPLOYEE tip (TrinkgeldAN) is non-taxable; an OWNER tip (TrinkgeldAG) is taxed into the
 * item rates and is already inside `vatDetails`.
 *
 * A bill closed BEFORE the 2026-09-16 change stored the employee tip as a 0 % `BillVatDetail` row,
 * so its VAT rows already sum to the full `paidAmount`; re-deriving its payload (e.g. via
 * `TsePendingSweeper`) must not add the tip a second time. A bill closed after the change sums to
 * `paidAmount − tips`. That difference is the discriminator — it needs no schema change and cannot
 * be confused with a genuine 0 %-rated item, whose row is part of `billAmount` either way.
 *
 * Requires an ambient transaction: `bill.vatDetails` is a lazy Exposed referrer.
 */
object NonTaxableTipCarrier {
    fun outsideVatDetails(bill: Bill): BigDecimal {
        if (bill.tipRecipient != TipRecipient.EMPLOYEE) return BigDecimal.ZERO
        if (bill.tips.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO
        val vatSum = bill.vatDetails.sumOf { it.totalAmount }
        val tipAlreadyInVatDetails = vatSum.compareTo(bill.paidAmount) == 0
        return if (tipAlreadyInVatDetails) BigDecimal.ZERO else bill.tips
    }
}
