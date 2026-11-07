package org.example.project.features.supplier.data.service

import org.example.project.core.utils.TaxNumberFormat
import org.example.project.core.utils.VatIdFormat
import org.example.project.features.document.domain.models.Invoice
import org.example.project.features.partner.domain.models.BusinessPartner
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.supplier.domain.models.Supplier
import org.example.project.features.supplier.domain.repository.SupplierRepository

/**
 * Resolves an invoice's asserted seller to a creditor, in a fixed order of decreasing certainty:
 * **VAT ID → tax number → known IBAN → normalised name.**
 *
 * A VAT ID that matches nothing auto-creates a confirmed supplier: EN 16931 BT-31 is exact, so
 * structured invoices dedupe perfectly. Without one, an exact normalised-name hit links, and
 * anything else creates a **provisional** supplier for a human to confirm — auto-creating from a
 * bare OCR'd name is how "Metro AG", "METRO AG" and "Metro Ag" become three creditors.
 *
 * Resolution runs in **two stages**: the ladder finds the *company* (`business_partner`), and a
 * creditor role is then ensured on it. Splitting it that way is what lets an invoice from a firm we
 * already sell to attach a role to the existing company rather than mint a duplicate of it.
 *
 * Master data is never overwritten by an invoice. A resolved supplier keeps its own name and
 * address; only the IBAN set grows. Nor is an existing **link** overwritten: an invoice that already
 * carries a supplier is returned as-is, so re-resolving it is a read.
 *
 * Requires an ambient transaction.
 */
class SupplierResolver(
    private val suppliers: SupplierRepository,
) {
    fun resolveAndLink(invoice: Invoice): SupplierResolution {
        // A link, once made, is never silently replaced — the invariant lives here rather than at
        // the call sites, so a re-parse of an already-linked invoice (ParsedInvoiceWriter's update
        // leg) cannot move it to a different creditor behind an operator's back. The IBAN check
        // still runs: a re-issued invoice quoting a new bank account is exactly the case worth
        // reporting. Nothing is written.
        invoice.supplier?.let { linked ->
            return SupplierResolution(linked, created = false, ibanWasUnknown = ibanIsUnknownTo(linked, invoice))
        }

        // Two stages. The party is the company; the creditor role is one direction of trade with it.
        // Where the party already exists as a debtor, this attaches a role instead of creating a
        // second company — which is the whole point of the model. Before it, a company we already
        // sold to was invisible here, so its invoice minted a duplicate whose payables and
        // receivables could never be seen together.
        val party = findParty(invoice) ?: createPartyFrom(invoice) ?: return SupplierResolution(null, false, false)
        val existingRole = suppliers.creditorRoleFor(party)
        val ibanWasUnknown = existingRole != null && ibanIsUnknownTo(existingRole, invoice)

        val supplier =
            existingRole ?: suppliers.createCreditorRole(party, isProvisional = invoice.sellerVatId.isNullOrBlank())

        invoice.supplier = supplier
        invoice.paymentIban?.takeIf { it.isNotBlank() }?.let {
            suppliers.registerBankAccount(supplier, it, bic = null, invoiceId = invoice.id.value)
        }

        return SupplierResolution(supplier, created = existingRole == null, ibanWasUnknown = ibanWasUnknown)
    }

    private fun ibanIsUnknownTo(
        supplier: Supplier,
        invoice: Invoice,
    ): Boolean =
        !invoice.paymentIban.isNullOrBlank() &&
            !suppliers.knowsIban(supplier, invoice.paymentIban)

    /**
     * VAT id, then tax number, then IBAN, then normalized name — the order that was already here,
     * now asking "which company?" rather than "which creditor?".
     *
     * The IBAN leg only ever *matches*; it never creates. That matters more than it used to: once
     * debtor-side IBAN capture exists, this searches accounts a human entered, and a shared platform
     * or factoring IBAN would otherwise pull an invoice onto the wrong company.
     */
    private fun findParty(invoice: Invoice): BusinessPartner? =
        suppliers.findPartner(invoice.sellerVatId, invoice.sellerTaxNumber, name = null)
            ?: suppliers.findPartnerByIban(invoice.paymentIban)
            ?: suppliers.findPartner(vatId = null, taxNumber = null, name = invoice.sellerName)

    /** Null when the invoice names no seller at all — there is nothing to create a company from. */
    private fun createPartyFrom(invoice: Invoice): BusinessPartner? {
        val name = invoice.sellerName?.takeIf { it.isNotBlank() } ?: return null
        return BusinessPartner.new {
            // Single-shop assumption, the same one `SupplierRepositoryImpl.create` states: this path
            // has no shop to hand, so it takes the only one that exists.
            this.shop = ShopInfo.all().first()
            this.name = name
            this.normalizedName = normalizeSupplierName(name)
            // Canonicalized like every other writer of this column: what an invoice asserts
            // is the rawest input we have, and the next invoice's lookup compares against it
            // with an exact `eq`.
            this.vatId = invoice.sellerVatId?.let { VatIdFormat.canonical(it) }?.takeIf { it.isNotBlank() }
            // Canonicalized like the VAT id, and for the sharper version of the same reason: an
            // XML text node routinely carries surrounding whitespace, so what an invoice asserts is
            // the likeliest value of all to differ only in punctuation from what is already stored.
            this.taxNumber = invoice.sellerTaxNumber?.let { TaxNumberFormat.canonical(it) }?.takeIf { it.isNotBlank() }
            this.street = invoice.sellerStreet
            this.zip = invoice.sellerZip
            this.city = invoice.sellerCity
            this.country = invoice.sellerCountry
            this.email = invoice.sellerEmail
            this.phone = invoice.sellerPhone
            // No `legalName`: EN 16931 gives one seller name (BT-27) and `Invoice` stores exactly
            // that, so there is no second, more formal name to carry here.
        }
    }
}
