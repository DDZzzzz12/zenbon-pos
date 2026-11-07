package org.example.project.features.customexport.domain.catalogue

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.database.tables.BillVatDetailTable
import org.example.project.features.customer.database.tables.CustomerTable
import org.example.project.features.debtor.database.tables.DebtorTable
import org.example.project.features.document.database.tables.InvoiceBookingTable
import org.example.project.features.document.database.tables.InvoiceTable
import org.example.project.features.employee.database.tables.EmployeeInfoTable
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.kassenbuch.database.tables.CashBookTable
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
import org.example.project.features.kassenbuch.database.tables.KassenbuchLineItemTable
import org.example.project.features.partner.database.tables.BusinessPartnerTable
import org.example.project.features.product.database.tables.ProductCategoryTable
import org.example.project.features.product.database.tables.ProductTable
import org.example.project.features.salesinvoice.database.tables.SalesInvoiceBookingTable
import org.example.project.features.salesinvoice.database.tables.SalesInvoiceLineTable
import org.example.project.features.salesinvoice.database.tables.SalesInvoiceTable
import org.example.project.features.salesinvoice.database.tables.SalesInvoiceVatBreakdownTable
import org.example.project.features.supplier.database.tables.SupplierBankAccountTable
import org.example.project.features.supplier.database.tables.SupplierTable
import org.example.project.features.voucher.database.tables.VoucherTable
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table

/**
 * Every table an export may read.
 *
 * **Default-deny**: a table absent from here is not exportable, and — the part worth stating
 * separately — is not *traversed* either. The schema graph stops dead at an unregistered table, so
 * registration is the whole permission surface rather than only a display filter. A table added to
 * this schema next month is invisible to exports until somebody deliberately writes an entry.
 *
 * [ForbiddenExportTargets] remains an independent second lock for data nobody should ever register;
 * `ExportTableRegistryTest` fails if the two ever both claim the same table.
 */
object ExportTableRegistry {
    /**
     * `invoice.issue_date` as a non-null column, for [ExportTable.periodColumn].
     *
     * The same cast [org.example.project.features.customexport.domain.catalogue.subjects.IncomingInvoiceSubject]
     * makes, for the same reason and with the same consequence: the column is nullable, the property is
     * typed non-null, the cast is erased at runtime, and the only thing ever built against it is a
     * `>=` / `<=` pair. An invoice with no issue date therefore satisfies neither bound and falls out
     * of a period-scoped aggregate — which is the honest answer, because there is no date on which to
     * claim it falls inside the operator's Zeitraum.
     *
     * Declared **before** [entries]: an `object`'s properties initialise top to bottom, so a reference
     * from [entries] to a val written below it would read null.
     */
    @Suppress("UNCHECKED_CAST")
    private val invoiceIssueDate = InvoiceTable.issueDate as Column<LocalDateTime>

    val entries: List<ExportTable> =
        listOf(
            ExportTable(
                table = BusinessPartnerTable,
                label = "Geschäftspartner",
                capability = Capability.CREDITOR_READ,
                columns =
                    listOf(
                        ExportColumn(BusinessPartnerTable.name, "Name"),
                        ExportColumn(BusinessPartnerTable.legalName, "Firmierung"),
                        ExportColumn(BusinessPartnerTable.vatId, "USt-IdNr."),
                        ExportColumn(BusinessPartnerTable.taxNumber, "Steuernummer"),
                        ExportColumn(BusinessPartnerTable.street, "Straße"),
                        ExportColumn(BusinessPartnerTable.zip, "PLZ"),
                        ExportColumn(BusinessPartnerTable.city, "Ort"),
                        ExportColumn(BusinessPartnerTable.country, "Land"),
                        ExportColumn(BusinessPartnerTable.email, "E-Mail"),
                        ExportColumn(BusinessPartnerTable.phone, "Telefon"),
                        ExportColumn(BusinessPartnerTable.createdAt, "Angelegt am", ExportFieldType.DATE),
                    ),
            ),
            ExportTable(
                table = SupplierTable,
                label = "Kreditor-Rolle",
                capability = Capability.CREDITOR_READ,
                columns =
                    listOf(
                        ExportColumn(SupplierTable.creditorNo, "Kreditorennummer"),
                        ExportColumn(SupplierTable.defaultAccountNo, "Sachkonto"),
                        ExportColumn(SupplierTable.defaultBuCode, "BU-Schlüssel"),
                        ExportColumn(SupplierTable.defaultCostCenter, "Kostenstelle"),
                        ExportColumn(SupplierTable.paymentTermsDays, "Zahlungsziel (Tage)", ExportFieldType.NUMBER),
                        ExportColumn(SupplierTable.sepaCreditorId, "SEPA-Gläubiger-ID"),
                        ExportColumn(SupplierTable.isProvisional, "Provisorisch", ExportFieldType.BOOLEAN),
                        ExportColumn(SupplierTable.isActive, "Aktiv", ExportFieldType.BOOLEAN),
                    ),
            ),
            // The AR mirror of the creditor role, and the first entry carrying a capability other
            // than CREDITOR_READ. Until it, every registered table carried the same one, so no
            // reachable path could tell the per-path capability rule from the per-field one.
            //
            // Offerable at all only because `debtor.partner_id` is UNIQUE: the graph reads
            // that index to derive the relation as to-one. Before it, the Debitorennummer was
            // unreachable on a partner export for want of an index, not for want of a decision.
            ExportTable(
                table = DebtorTable,
                label = "Debitor-Rolle",
                capability = Capability.DEBTOR_READ,
                columns =
                    listOf(
                        ExportColumn(DebtorTable.debtorNo, "Debitorennummer"),
                        ExportColumn(DebtorTable.defaultRevenueAccountNo, "Erlöskonto"),
                        ExportColumn(DebtorTable.defaultBuCode, "BU-Schlüssel"),
                        ExportColumn(DebtorTable.paymentTermsDays, "Zahlungsziel (Tage)", ExportFieldType.NUMBER),
                        ExportColumn(DebtorTable.einvoiceFormat, "E-Rechnungsformat"),
                        ExportColumn(DebtorTable.isActive, "Aktiv", ExportFieldType.BOOLEAN),
                    ),
            ),
            // Registered deliberately although nothing can be exported from it today.
            //
            // `supplier_bank_account` is UNIQUE(partner_id, iban) — many accounts per company — so
            // the graph derives the relation as to-many, `ExportSubject.paths` filters it out and no
            // field of it is ever offered. `ManyRelationIsNotOfferedTest` holds that.
            //
            // Discovery and exposure are separate concerns: registration is what lets the walk *see*
            // the path at all, and the later aggregate slice — which will offer a to-many relation as
            // a correlated subquery rather than a join — should find it already mapped instead of
            // having to rediscover it. The columns below are what that slice will aggregate.
            ExportTable(
                table = SupplierBankAccountTable,
                label = "Bankverbindung",
                capability = Capability.CREDITOR_READ,
                columns =
                    listOf(
                        ExportColumn(SupplierBankAccountTable.iban, "IBAN"),
                        ExportColumn(SupplierBankAccountTable.bic, "BIC"),
                        ExportColumn(SupplierBankAccountTable.verified, "Bestätigt", ExportFieldType.BOOLEAN),
                    ),
                // No period column: an IBAN is not an event. `first_seen_on_invoice_id` records where
                // we noticed one, not when the company opened it, so scoping these to a Zeitraum would
                // answer a question nobody asked. They are offered once, unscoped.
                aggregates =
                    listOf(
                        ExportAggregate(
                            key = "list",
                            function = AggregateFunction.LIST,
                            column = SupplierBankAccountTable.iban,
                            label = "IBANs",
                            type = ExportFieldType.TEXT,
                            orderColumn = SupplierBankAccountTable.iban,
                        ),
                        ExportAggregate(
                            key = "count",
                            function = AggregateFunction.COUNT,
                            column = null,
                            label = "Anzahl Bankverbindungen",
                            type = ExportFieldType.NUMBER,
                        ),
                    ),
            ),
            ExportTable(
                table = InvoiceTable,
                label = "Eingangsrechnung",
                capability = Capability.CREDITOR_READ,
                columns =
                    listOf(
                        ExportColumn(InvoiceTable.invoiceNumber, "Rechnungsnummer"),
                        ExportColumn(InvoiceTable.issueDate, "Rechnungsdatum", ExportFieldType.DATE),
                        ExportColumn(InvoiceTable.dueDate, "Fälligkeit", ExportFieldType.DATE),
                        // The catalogue's first MONEY field, and `tax_inclusive_total` is a
                        // `decimal(19,4)` — which is the precondition, not a detail: MONEY skips the
                        // CSV formula guard and the renderer refuses anything but a `BigDecimal`.
                        ExportColumn(InvoiceTable.taxInclusiveTotal, "Bruttobetrag", ExportFieldType.MONEY),
                    ),
                // What a partner's invoices reduce to when the relation is aggregated rather than
                // joined. `Rechnungssumme` is MONEY on the same precondition the Bruttobetrag column
                // states: `tax_inclusive_total` is a `decimal(19,4)`, so SUM over it stays a
                // `BigDecimal`, which is the only thing the MONEY renderer accepts.
                aggregates =
                    listOf(
                        ExportAggregate(
                            key = "count",
                            function = AggregateFunction.COUNT,
                            column = null,
                            label = "Anzahl Rechnungen",
                            type = ExportFieldType.NUMBER,
                        ),
                        ExportAggregate(
                            key = "sum",
                            function = AggregateFunction.SUM,
                            column = InvoiceTable.taxInclusiveTotal,
                            label = "Rechnungssumme",
                            type = ExportFieldType.MONEY,
                        ),
                        ExportAggregate(
                            key = "first",
                            function = AggregateFunction.MIN,
                            column = InvoiceTable.issueDate,
                            label = "Erste Rechnung",
                            type = ExportFieldType.DATE,
                        ),
                        ExportAggregate(
                            key = "last",
                            function = AggregateFunction.MAX,
                            column = InvoiceTable.issueDate,
                            label = "Letzte Rechnung",
                            type = ExportFieldType.DATE,
                        ),
                    ),
                periodColumn = invoiceIssueDate,
            ),
            ExportTable(
                table = InvoiceBookingTable,
                label = "Buchung",
                capability = Capability.CREDITOR_READ,
                columns =
                    listOf(
                        ExportColumn(InvoiceBookingTable.accountNo, "Gegenkonto"),
                        ExportColumn(InvoiceBookingTable.bpAccountNo, "Kreditorenkonto"),
                        ExportColumn(InvoiceBookingTable.buCode, "BU-Schlüssel"),
                        ExportColumn(InvoiceBookingTable.bookingText, "Buchungstext"),
                        ExportColumn(InvoiceBookingTable.costCategoryId, "Kostenstelle"),
                        ExportColumn(InvoiceBookingTable.paidAt, "Bezahlt am", ExportFieldType.DATE),
                    ),
            ),
            // --- Ausgangsrechnungen: the AR mirror of the four AP entries above. --------------------
            //
            // Gated by INVOICE_READ, which is what `InvoiceRules` requires of
            // `GET /api/v1/sales-invoices`: an export must never be an easier way to read data than
            // the screen showing it. Deliberately **not** DEBTOR_READ — that gates the debtor *role*
            // (Debitorennummer, Erlöskonto, payment terms), and a party's bookkeeping role and the
            // invoices we issued them are different data behind different gates.
            //
            // Three things are registered nowhere below, and their absence is the decision:
            //
            //  - the `tse_*` block. It is the original bill's fiscal signature, snapshotted at issue.
            //    A CSV carrying it would resemble fiscal evidence without being it, and a correct TSE
            //    export already exists. `ForbiddenExportTargets.COLUMN_PREFIXES` denies the whole
            //    prefix as an independent second lock, so registering one here fails a build.
            //  - `notes`, free text an operator typed for their own use, of no value to a recipient
            //    and the one place on this table where anything at all might have been written down.
            //  - `issued_by` / `cancelled_by`. They reference `employees`, and traversing there to
            //    put a name in a revenue export is a staff-data hop nobody asked for. The columns are
            //    the FK ids, so registering them would publish opaque numbers anyway.
            ExportTable(
                table = SalesInvoiceTable,
                label = "Ausgangsrechnung",
                capability = Capability.INVOICE_READ,
                columns =
                    listOf(
                        ExportColumn(SalesInvoiceTable.invoiceNumber, "Rechnungsnummer"),
                        ExportColumn(SalesInvoiceTable.issueDate, "Rechnungsdatum", ExportFieldType.DATE),
                        // Leistungszeitpunkt, §14 Abs. 4 Nr. 6 — the date the tax period follows,
                        // which is not always the date the invoice was written.
                        ExportColumn(SalesInvoiceTable.serviceDate, "Leistungsdatum", ExportFieldType.DATE),
                        ExportColumn(SalesInvoiceTable.dueDate, "Fälligkeit", ExportFieldType.DATE),
                        ExportColumn(SalesInvoiceTable.buyerName, "Käufer"),
                        ExportColumn(SalesInvoiceTable.buyerVatId, "USt-IdNr. des Käufers"),
                        ExportColumn(SalesInvoiceTable.buyerCity, "Ort des Käufers"),
                        ExportColumn(SalesInvoiceTable.status, "Status"),
                        ExportColumn(SalesInvoiceTable.currency, "Währung"),
                        // Four MONEY columns on the same precondition the Eingangsrechnung's
                        // Bruttobetrag states: each is a `decimal(19,4)`, so the value reaching the
                        // renderer is a `BigDecimal` — the only thing MONEY accepts, and the reason
                        // the type may not be inferred from a column merely being numeric.
                        ExportColumn(SalesInvoiceTable.taxExclusiveTotal, "Nettobetrag", ExportFieldType.MONEY),
                        ExportColumn(SalesInvoiceTable.taxTotal, "Steuerbetrag", ExportFieldType.MONEY),
                        ExportColumn(SalesInvoiceTable.taxInclusiveTotal, "Bruttobetrag", ExportFieldType.MONEY),
                        ExportColumn(SalesInvoiceTable.openAmount, "Offener Betrag", ExportFieldType.MONEY),
                    ),
                aggregates =
                    listOf(
                        ExportAggregate(
                            key = "count",
                            function = AggregateFunction.COUNT,
                            column = null,
                            label = "Anzahl Ausgangsrechnungen",
                            type = ExportFieldType.NUMBER,
                        ),
                        ExportAggregate(
                            key = "sum",
                            function = AggregateFunction.SUM,
                            column = SalesInvoiceTable.taxInclusiveTotal,
                            label = "Umsatz",
                            type = ExportFieldType.MONEY,
                        ),
                        ExportAggregate(
                            key = "first",
                            function = AggregateFunction.MIN,
                            column = SalesInvoiceTable.issueDate,
                            label = "Erste Ausgangsrechnung",
                            type = ExportFieldType.DATE,
                        ),
                        ExportAggregate(
                            key = "last",
                            function = AggregateFunction.MAX,
                            column = SalesInvoiceTable.issueDate,
                            label = "Letzte Ausgangsrechnung",
                            type = ExportFieldType.DATE,
                        ),
                    ),
                // No cast, unlike `invoiceIssueDate` above: `sales_invoice.issue_date` is NOT NULL,
                // so the property is already the non-null type `periodColumn` wants. The AP side's
                // `@Suppress("UNCHECKED_CAST")` is a workaround for a nullable column and must not be
                // copied where the schema does not need it.
                periodColumn = SalesInvoiceTable.issueDate,
            ),
            // Our posting decisions about an outgoing invoice, and the AR mirror of `invoice_booking`
            // in every respect that matters here: `uniqueIndex(salesInvoiceId)` is what makes the
            // graph derive the relation as to-one, so these columns are offered as columns.
            ExportTable(
                table = SalesInvoiceBookingTable,
                label = "Buchung (Ausgang)",
                capability = Capability.INVOICE_READ,
                columns =
                    listOf(
                        ExportColumn(SalesInvoiceBookingTable.revenueAccountNo, "Erlöskonto"),
                        // "Personenkonto", not "Debitorenkonto": the column holds the account actually
                        // used at posting time, which may be the shop's collective debtor rather than
                        // the party's own — a heading naming the debtor would assert more than the
                        // value carries.
                        ExportColumn(SalesInvoiceBookingTable.bpAccountNo, "Personenkonto"),
                        ExportColumn(SalesInvoiceBookingTable.buCode, "BU-Schlüssel"),
                        ExportColumn(SalesInvoiceBookingTable.costCenter, "Kostenstelle"),
                        ExportColumn(SalesInvoiceBookingTable.bookingText, "Buchungstext"),
                        ExportColumn(SalesInvoiceBookingTable.postedAt, "Gebucht am", ExportFieldType.DATE),
                    ),
            ),
            // Both of the following are to-many — an invoice has many lines and many VAT rates — so
            // the graph offers them as **aggregates only** and their columns never appear. The columns
            // are registered all the same: registration is what lets the walk see the table at all,
            // and it is what a future line-level subject rooted here would build on.
            //
            // No period column on either: a line has no date of its own. Its date is the invoice's,
            // one hop up, and scoping a line count to a Zeitraum by a column it does not have would
            // answer a question nobody asked.
            ExportTable(
                table = SalesInvoiceLineTable,
                label = "Rechnungsposition (Ausgang)",
                capability = Capability.INVOICE_READ,
                columns =
                    listOf(
                        ExportColumn(SalesInvoiceLineTable.lineNo, "Positionsnummer", ExportFieldType.NUMBER),
                        ExportColumn(SalesInvoiceLineTable.itemName, "Bezeichnung"),
                        // NUMBER, not MONEY: a quantity and an amount have the same SQL type and
                        // different rules, and this is exactly the pair `ExportColumn.type` warns about.
                        ExportColumn(SalesInvoiceLineTable.quantity, "Menge", ExportFieldType.NUMBER),
                        ExportColumn(SalesInvoiceLineTable.netUnitPrice, "Einzelpreis netto", ExportFieldType.MONEY),
                        ExportColumn(SalesInvoiceLineTable.lineNetAmount, "Nettobetrag", ExportFieldType.MONEY),
                        ExportColumn(SalesInvoiceLineTable.vatRate, "Steuersatz", ExportFieldType.NUMBER),
                        ExportColumn(SalesInvoiceLineTable.vatAmount, "Steuerbetrag", ExportFieldType.MONEY),
                        ExportColumn(SalesInvoiceLineTable.lineGrossAmount, "Bruttobetrag", ExportFieldType.MONEY),
                    ),
                aggregates =
                    listOf(
                        ExportAggregate(
                            key = "count",
                            function = AggregateFunction.COUNT,
                            column = null,
                            label = "Anzahl Positionen",
                            type = ExportFieldType.NUMBER,
                        ),
                    ),
            ),
            ExportTable(
                table = SalesInvoiceVatBreakdownTable,
                label = "Steueraufteilung (Ausgang)",
                capability = Capability.INVOICE_READ,
                columns =
                    listOf(
                        ExportColumn(SalesInvoiceVatBreakdownTable.vatCategoryCode, "Steuerkategorie"),
                        ExportColumn(SalesInvoiceVatBreakdownTable.vatRate, "Steuersatz", ExportFieldType.NUMBER),
                        ExportColumn(SalesInvoiceVatBreakdownTable.taxableAmount, "Nettobetrag", ExportFieldType.MONEY),
                        ExportColumn(SalesInvoiceVatBreakdownTable.vatAmount, "Steuerbetrag", ExportFieldType.MONEY),
                        ExportColumn(SalesInvoiceVatBreakdownTable.exemptionReasonText, "Steuerbefreiungsgrund"),
                    ),
                aggregates =
                    listOf(
                        ExportAggregate(
                            key = "count",
                            function = AggregateFunction.COUNT,
                            column = null,
                            label = "Anzahl Steuersätze",
                            type = ExportFieldType.NUMBER,
                        ),
                    ),
            ),
            // --- Stammdaten: Produkte, Kunden, Gutscheine. ------------------------------------------
            //
            // Three master-data tables an advisor or an operator asks for as a flat list, and the
            // lowest-risk registrations in the catalogue: none of them carries fiscal evidence, and
            // none of them is written by the signing path.
            //
            // Gated by CATALOG_READ, which is what `CatalogRules` requires of `GET /api/products` —
            // the same rule the till reads the menu under. Nothing narrower exists, and nothing
            // wider is defensible: an export must never be an easier way to read data than the
            // screen showing it.
            //
            // `created_at` is registered nowhere below because `product` has none — only
            // `updated_at`, which is why the Produkte subject declares no `dateColumn` at all rather
            // than pointing a Zeitraum at a column that moves every time somebody edits a price.
            //
            // Left out deliberately: `en_name`/`ch_name` (the till's translations, not data about
            // the article), `description` and `image_url`, `is_deposit`/`is_deposit_return`/
            // `allow_custom_price` (internal behaviour flags that read as nonsense in a CSV), and
            // the two foreign-key ids `category_id`/`pfand_type_id` — a bare integer tells an
            // operator nothing, and the category arrives by name through its own group below.
            ExportTable(
                table = ProductTable,
                label = "Produkt",
                capability = Capability.CATALOG_READ,
                columns =
                    listOf(
                        ExportColumn(ProductTable.productNumber, "Artikelnummer"),
                        ExportColumn(ProductTable.name, "Name"),
                        ExportColumn(ProductTable.code, "Produktcode"),
                        // MONEY on the precondition the type demands, checked and not assumed:
                        // `product.price` is a `decimal(19,4)`, so the value reaching the renderer is
                        // a `BigDecimal` — the only thing MONEY accepts, and never a `Double`.
                        ExportColumn(ProductTable.price, "Preis", ExportFieldType.MONEY),
                        ExportColumn(ProductTable.productType, "Produktart"),
                        ExportColumn(ProductTable.isActive, "Aktiv", ExportFieldType.BOOLEAN),
                        // Not "Angelegt am": this column moves on every edit. Labelling it as a
                        // creation date would put a wrong answer in a column an advisor sorts by.
                        ExportColumn(ProductTable.updatedAt, "Zuletzt geändert am", ExportFieldType.DATE),
                    ),
            ),
            // Registered so a product export can carry its category by **name** rather than by the
            // opaque `category_id`. The relation is to-one from the product's side, so these arrive
            // as ordinary columns in their own group; the reverse direction (a category's many
            // products) can only ever be walked from a category root, and no subject has one.
            //
            // Only two columns: `sort_position` and `visibility` configure how the till renders the
            // menu, and `description`/`en_name`/`ch_name` are not data about the category either.
            ExportTable(
                table = ProductCategoryTable,
                label = "Warengruppe",
                capability = Capability.CATALOG_READ,
                columns =
                    listOf(
                        ExportColumn(ProductCategoryTable.name, "Name"),
                        ExportColumn(ProductCategoryTable.isActive, "Aktiv", ExportFieldType.BOOLEAN),
                    ),
            ),
            // Gated by CUSTOMER_LIST, **not** CUSTOMER_READ. The two are split precisely along the
            // line this feature sits on: `CustomerRules` gives `GET /api/customers/{id}` and
            // `/search` — looking one person up, which is till work — `CUSTOMER_READ`, and reserves
            // `CUSTOMER_LIST` for the unrestricted list, because "walking the whole base is not"
            // till work. An export **is** enumeration, of every row at once and into a file that
            // leaves the building, so it costs the enumeration capability. Registering it under
            // `CUSTOMER_READ` would hand every terminal login the customer database in one CSV —
            // defect D4 re-opened through a side door, and a GDPR question as much as a security one.
            //
            // There is no address here to register: `customers` holds name, contact and consent
            // only, and the postal addresses live in `customer_addresses`, which is not registered.
            //
            // `notes` is left out — free text an operator typed for their own use — and so is
            // `data_processing_consent`, which is the internal GDPR bookkeeping flag rather than
            // something a recipient of this file acts on.
            ExportTable(
                table = CustomerTable,
                label = "Kunde",
                capability = Capability.CUSTOMER_LIST,
                columns =
                    listOf(
                        ExportColumn(CustomerTable.customerNumber, "Kundennummer"),
                        ExportColumn(CustomerTable.firstName, "Vorname"),
                        ExportColumn(CustomerTable.lastName, "Nachname"),
                        ExportColumn(CustomerTable.email, "E-Mail"),
                        ExportColumn(CustomerTable.phone, "Telefon"),
                        // Registered on purpose, next to the e-mail address rather than left out as
                        // "a flag": a list of addresses without it invites a mailing to people who
                        // never consented to one. The column is what lets the operator take only
                        // those who did.
                        ExportColumn(CustomerTable.marketingConsent, "Werbe-Einwilligung", ExportFieldType.BOOLEAN),
                        ExportColumn(CustomerTable.lastPurchaseAt, "Letzter Einkauf", ExportFieldType.DATE),
                        ExportColumn(CustomerTable.isActive, "Aktiv", ExportFieldType.BOOLEAN),
                        ExportColumn(CustomerTable.createdAt, "Angelegt am", ExportFieldType.DATE),
                    ),
            ),
            // Gated by VOUCHER_REPORT, **not** VOUCHER_READ — the same reasoning as the customer
            // entry above, and the same split already written into `VoucherRules`: `VOUCHER_READ` is
            // the till validating one code and reading that one voucher's redemption history, while
            // the shop-wide rollups (`/statistics`, `/created-by-date`, `/redeemed-by-date`) carry
            // `VOUCHER_REPORT` so a terminal login cannot read them. A voucher export with a summed
            // remaining value *is* those rollups, in a file; it costs what they cost.
            //
            // `invalidation_reason` is free text and is left out; `invalidation_source` says which
            // subsystem invalidated the row, which is our plumbing rather than the operator's data.
            ExportTable(
                table = VoucherTable,
                label = "Gutschein",
                capability = Capability.VOUCHER_REPORT,
                columns =
                    listOf(
                        ExportColumn(VoucherTable.voucherNumber, "Gutscheinnummer"),
                        ExportColumn(VoucherTable.status, "Status"),
                        // Both MONEY, and both confirmed rather than assumed: `original_value` and
                        // `remaining_value` are `decimal(10,2)`, so each reaches the renderer as a
                        // `BigDecimal`. A voucher value never passes through a `Double`.
                        ExportColumn(VoucherTable.originalValue, "Ausgabewert", ExportFieldType.MONEY),
                        ExportColumn(VoucherTable.remainingValue, "Restwert", ExportFieldType.MONEY),
                        ExportColumn(VoucherTable.createdAt, "Ausgegeben am", ExportFieldType.DATE),
                        ExportColumn(VoucherTable.fullyRedeemedAt, "Vollständig eingelöst am", ExportFieldType.DATE),
                        ExportColumn(VoucherTable.invalidatedAt, "Storniert am", ExportFieldType.DATE),
                    ),
                // The figure an advisor actually asks for: what the shop still owes in vouchers it
                // has already been paid for. `sum` reduces the **remaining** value, not the issued
                // one — the outstanding liability is what is left on the cards, and summing
                // `original_value` would report a debt already partly settled as still open.
                //
                // MONEY on the same precondition the columns state: `remaining_value` is a
                // `decimal(10,2)`, so SUM over it stays a `BigDecimal`.
                aggregates =
                    listOf(
                        ExportAggregate(
                            key = "count",
                            function = AggregateFunction.COUNT,
                            column = null,
                            label = "Anzahl Gutscheine",
                            type = ExportFieldType.NUMBER,
                        ),
                        ExportAggregate(
                            key = "sum",
                            function = AggregateFunction.SUM,
                            column = VoucherTable.remainingValue,
                            label = "Offener Gutscheinwert",
                            type = ExportFieldType.MONEY,
                        ),
                        ExportAggregate(
                            key = "first",
                            function = AggregateFunction.MIN,
                            column = VoucherTable.createdAt,
                            label = "Erster Gutschein",
                            type = ExportFieldType.DATE,
                        ),
                        ExportAggregate(
                            key = "last",
                            function = AggregateFunction.MAX,
                            column = VoucherTable.createdAt,
                            label = "Letzter Gutschein",
                            type = ExportFieldType.DATE,
                        ),
                    ),
                // `created_at` — the issue date — and not `fully_redeemed_at` or `invalidated_at`.
                // It is the only date every voucher has: the other two are null until something
                // happens to the row, so scoping by either would silently drop every voucher still
                // in circulation, which is exactly the population an outstanding-liability figure is
                // about. "In diesem Zeitraum" over vouchers means the ones issued then.
                //
                // No cast, unlike `invoiceIssueDate` above: `voucher.created_at` is NOT NULL.
                periodColumn = VoucherTable.createdAt,
            ),
            // --- Kassenbelege und Kassenbuch: the two fiscal look-alikes. -----------------------------
            //
            // Both already have a **correct compliance export** — DSFinV-K and the TSE export for the
            // bills, DATEV XML and the Buchungsstapel for the cash book. Nothing below is shaped to
            // resemble either one, and that constraint decided most of what was left out.
            //
            // What the TSE check found, stated rather than implied: **neither table carries signature
            // material inline.** Each declares exactly two `tse_`-prefixed columns — `tse_transaction_id`
            // (a foreign key) and `tse_signing_status` (the Nachsignieren queue state) — and the
            // signature itself lives in `tse_transaction`, which `ForbiddenExportTargets.TABLES` denies
            // and nothing registers, so the walk cannot reach it. `COLUMN_PREFIXES` gained `bill.tse_`
            // and `kassenbuch_header.tse_` all the same; see its KDoc for why the shape is denied even
            // where today's instance is harmless.
            //
            // `bill` is the largest table in this schema by row count, so the 100k row cap will
            // genuinely bite. That is a reason to keep the column set narrow, not a reason to widen it.
            ExportTable(
                table = BillTable,
                label = "Kassenbeleg",
                capability = Capability.BILL_READ,
                columns =
                    listOf(
                        ExportColumn(BillTable.billNumber, "Belegnummer"),
                        // `end_time` is when the bill was closed — the moment of the sale. `start_time`
                        // is when the table sat down, which is a service-duration figure and not what
                        // an operator sorting a sales list means by "the date".
                        ExportColumn(BillTable.endTime, "Belegdatum", ExportFieldType.DATE),
                        // Five MONEY columns, and the precondition is checked rather than assumed:
                        // every one is a `decimal(19,4)`, so each reaches the renderer as a
                        // `BigDecimal`. A bill total never passes through a `Double`.
                        ExportColumn(BillTable.billAmount, "Bruttobetrag", ExportFieldType.MONEY),
                        ExportColumn(BillTable.discountAmount, "Rabatt", ExportFieldType.MONEY),
                        ExportColumn(BillTable.promotionAmount, "Aktionsabzug", ExportFieldType.MONEY),
                        // The payment state, and the only form this schema has one in: there is no
                        // status column on a bill, so "paid in full" is `paid_amount == bill_amount`
                        // and the operator reads it by putting the two columns side by side.
                        ExportColumn(BillTable.paidAmount, "Gezahlt", ExportFieldType.MONEY),
                        ExportColumn(BillTable.tips, "Trinkgeld", ExportFieldType.MONEY),
                        ExportColumn(BillTable.tipRecipient, "Trinkgeldempfänger"),
                        // Registered on purpose rather than filtered away silently: a training bill is
                        // not a sale, and an operator summing a column of Bruttobeträge that quietly
                        // includes them gets a total nobody can reconcile. The subject also offers a
                        // toggle; the column is what makes the toggle's effect visible in the file.
                        ExportColumn(BillTable.isTraining, "Schulungsbeleg", ExportFieldType.BOOLEAN),
                    ),
                // No aggregates. The only to-many route into `bill` is `sales_invoice → bill:by:
                // clears_sales_invoice_id`, the receivable clearings booked against one outgoing
                // invoice, and a column headed "Anzahl Kassenbelege" on a revenue list would be read as
                // a count of sales rather than of counter payments. Nothing is offered rather than
                // something misread.
                //
                // Left out, and each for its own reason:
                //  - the `tse_` pair, denied by prefix (see above).
                //  - `deleting_reason`, free text an operator typed while voiding a bill.
                //  - `start_time`, see the Belegdatum note.
                //  - `employee_id`, `table_session`, `terminal_id`, `zbon_id`, `deleted_bill_id`,
                //    `clears_sales_invoice_id` — all bare foreign-key
                //    ids. A number tells a recipient nothing, and `employee_id` in particular is a
                //    staff-data hop nobody asked for. That a bill is a storno or a Zahlartwechsel is
                //    genuinely worth knowing and is **not offerable today**: the schema says it only by
                //    the presence of an id, and this catalogue has no derived-column mechanism.
            ),
            // To-many from the bill — one row per VAT rate on the receipt — so the graph offers it as
            // aggregates only and its columns never appear. Registered all the same, on the convention
            // `sales_invoice_line` established: registration is what lets the walk see the table.
            //
            // No period column: a VAT line has no date of its own. Its date is the bill's, one hop up.
            ExportTable(
                table = BillVatDetailTable,
                label = "Steueraufteilung (Kassenbeleg)",
                capability = Capability.BILL_READ,
                columns =
                    listOf(
                        ExportColumn(BillVatDetailTable.netAmount, "Nettobetrag", ExportFieldType.MONEY),
                        ExportColumn(BillVatDetailTable.vatAmount, "Steuerbetrag", ExportFieldType.MONEY),
                        ExportColumn(BillVatDetailTable.totalAmount, "Bruttobetrag", ExportFieldType.MONEY),
                    ),
                aggregates =
                    listOf(
                        ExportAggregate(
                            key = "count",
                            function = AggregateFunction.COUNT,
                            column = null,
                            label = "Anzahl Steuersätze",
                            type = ExportFieldType.NUMBER,
                        ),
                        ExportAggregate(
                            key = "sum",
                            function = AggregateFunction.SUM,
                            column = BillVatDetailTable.vatAmount,
                            label = "Steuerbetrag",
                            type = ExportFieldType.MONEY,
                        ),
                    ),
            ),
            // The cash book an entry belongs to, so a Kassenbuch export can say "Ladenkasse" or
            // "Tresor" rather than leaving two books merged into one undifferentiated list. That is the
            // whole reason this table is registered — without it a shop with a Nebenkasse gets a file
            // that looks complete and is not.
            //
            // **A known pairwise restriction comes with it.** `kassenbuch_header` holds *two* foreign
            // keys into `cash_book` — `cash_book_id` and the Geldtransit counterparty — so the graph
            // derives two to-one paths to one table, both labelled "Kasse". Selecting a column from
            // each is refused by `requireOneRoutePerTable` with a sentence, because one FROM clause
            // holds a table once and joining it once would make one path's column read the other
            // path's row. Either alone works. This is the first registered subject that can produce
            // that pair; the guard was written for it and is now exercised by a test.
            //
            // Only four columns: `kost1`/`kost2` are the book's DATEV cost-centre *defaults* which an
            // entry may override, so exporting them next to entry data invites reading a default as
            // what was actually booked. `close_interval` is a hint never enforced, `sort_order`
            // configures the screen, and `shop_id` is a bare id.
            ExportTable(
                table = CashBookTable,
                label = "Kasse",
                capability = Capability.CASHBOOK_READ,
                columns =
                    listOf(
                        ExportColumn(CashBookTable.name, "Name"),
                        ExportColumn(CashBookTable.isFiscal, "Fiskalkasse", ExportFieldType.BOOLEAN),
                        ExportColumn(CashBookTable.datevAccount, "Sachkonto"),
                        ExportColumn(CashBookTable.isActive, "Aktiv", ExportFieldType.BOOLEAN),
                    ),
            ),
            // One logical receipt in the cash book. The header, not the line items, is the record an
            // operator means by "an entry": `belegNr` is unique to it, a linked PDF hangs off it, and
            // the running balance is stated on it.
            //
            // **Deliberately not a Buchungsstapel row.** `bu`, `gegenkonto`, `kost1`, `kost2` and
            // `waehrung` are exactly the DATEV posting fields, and a CSV carrying Belegnummer +
            // Belegdatum + Gegenkonto + BU-Schlüssel + Betrag *is* an EXTF row in all but the metadata
            // line — which is the thing a custom export must never be mistakable for. The real DATEV
            // XML and Buchungsstapel exports already exist and are gated separately. `nachricht` is
            // free text, `waehrung` is "EUR" on every row, and `transfer_id`/`is_zbon_aggregate` are
            // our plumbing.
            //
            // No aggregates and no period column: nothing to-many reaches the header. `cash_book →
            // kassenbuch_header` walks back into the subject's own root and the graph refuses it.
            ExportTable(
                table = KassenbuchHeaderTable,
                label = "Kassenbuch-Eintrag",
                capability = Capability.CASHBOOK_READ,
                columns =
                    listOf(
                        ExportColumn(KassenbuchHeaderTable.belegNr, "Belegnummer"),
                        // TEXT, not DATE, and the type is the point: `beleg_datum` is a `varchar(10)`
                        // holding "01.02.2026". Declaring it DATE would send a German date string
                        // through the ISO renderer, which is a lie about what the column holds even
                        // where the output happens to match.
                        ExportColumn(KassenbuchHeaderTable.belegDatum, "Belegdatum"),
                        ExportColumn(KassenbuchHeaderTable.entryType, "Buchungsart"),
                        ExportColumn(KassenbuchHeaderTable.belegtext, "Belegtext"),
                        // Both `decimal(19,4)`, so both reach the renderer as a `BigDecimal`.
                        ExportColumn(KassenbuchHeaderTable.totalAmount, "Betrag", ExportFieldType.MONEY),
                        ExportColumn(KassenbuchHeaderTable.currentBalance, "Saldo", ExportFieldType.MONEY),
                        // Null on an ordinary entry, REVERSAL or RE_ENTRY on the two rows a correction
                        // produces. Without it the pair reads as a duplicate booking.
                        ExportColumn(KassenbuchHeaderTable.correctionRole, "Korrekturart"),
                        ExportColumn(KassenbuchHeaderTable.isLocked, "Abgeschlossen", ExportFieldType.BOOLEAN),
                        ExportColumn(KassenbuchHeaderTable.createdAt, "Erfasst am", ExportFieldType.DATE),
                    ),
            ),
            // To-many from the header — one row per VAT rate within one receipt — so aggregates only,
            // exactly as `bill_vat_detail` and `sales_invoice_line`. A line has no date of its own, so
            // no period column.
            ExportTable(
                table = KassenbuchLineItemTable,
                label = "Kassenbuch-Position",
                capability = Capability.CASHBOOK_READ,
                columns =
                    listOf(
                        ExportColumn(KassenbuchLineItemTable.lineNumber, "Positionsnummer", ExportFieldType.NUMBER),
                        // `decimal(12,2)`, signed: negative is an outflow. MONEY, and the sign reaches
                        // the file unguarded — which is the whole reason MONEY skips the formula guard.
                        ExportColumn(KassenbuchLineItemTable.vorzBetrag, "Betrag", ExportFieldType.MONEY),
                        // TEXT: `ust_satz` is a `varchar(5)` holding "19" or "19,00", not a number.
                        ExportColumn(KassenbuchLineItemTable.ustSatz, "Steuersatz"),
                        ExportColumn(KassenbuchLineItemTable.belegtext, "Belegtext"),
                        ExportColumn(KassenbuchLineItemTable.kind, "Positionsart"),
                    ),
                aggregates =
                    listOf(
                        ExportAggregate(
                            key = "count",
                            function = AggregateFunction.COUNT,
                            column = null,
                            label = "Anzahl Positionen",
                            type = ExportFieldType.NUMBER,
                        ),
                        ExportAggregate(
                            key = "sum",
                            function = AggregateFunction.SUM,
                            column = KassenbuchLineItemTable.vorzBetrag,
                            label = "Positionssumme",
                            type = ExportFieldType.MONEY,
                        ),
                    ),
            ),
            // --- Personal: the staff list, and the narrowest entry in this catalogue. -----------------
            //
            // Gated by EMPLOYEE_READ, which is what `EmployeeRules` requires of
            // `GET /api/employees/summary` — the staff list itself — and of `GET /api/employee/info`,
            // the personnel-record list. Not `SELF_READ`: that is the rung every role holds for its
            // OWN row, and an export is every row at once. Not `EMPLOYEE_WRITE` either — reading a
            // list must not cost the capability to change it.
            //
            // ## This is personal data, and what is left out is the decision
            //
            // `employees.password_hash` and `employees.pin` are already denied by
            // [ForbiddenExportTargets.COLUMNS], so the credentials were never in question. The real
            // question is everything that is not a secret but is still about a person, and the answer
            // taken here is: a staff export answers **"who works here, in what role, since when"** and
            // nothing else. Anything arguable is left out, because adding a column later is a one-line
            // change and a personal-data leak is not undoable.
            //
            // Left out of `employees`, each for its own reason:
            //  - `username` — the other half of the credential pair whose `password_hash` is denied.
            //    Denied outright below rather than merely omitted; see `ForbiddenExportTargets`.
            //  - `last_login` — when each member of staff last signed in is behaviour monitoring
            //    (Leistungs- und Verhaltenskontrolle, § 87 Abs. 1 Nr. 6 BetrVG), not a staff list.
            //  - `notes` — unbounded free text an operator typed *about a person*. Nothing constrains
            //    what is in there, which is precisely why it must not travel in a file.
            //  - `personnel_number` — the payroll system's key. Meaningful only inside Lohnbuchhaltung,
            //    and `employee_number` is the identifier a staff list is keyed by.
            //  - `updated_at`, `language_preference`, `training_mode`, `in_rotation_pool` — record
            //    churn and till behaviour flags; they read as nonsense in a CSV.
            //  - `info_id`, `default_cost_center_id` — bare foreign-key ids. The personnel record
            //    arrives by name through its own group below.
            ExportTable(
                table = EmployeeTable,
                label = "Personal",
                capability = Capability.EMPLOYEE_READ,
                columns =
                    listOf(
                        ExportColumn(EmployeeTable.employeeNumber, "Personalnummer"),
                        ExportColumn(EmployeeTable.role, "Rolle"),
                        ExportColumn(EmployeeTable.employmentStatus, "Beschäftigungsstatus"),
                        // The record's creation, and labelled as that. The date somebody actually
                        // started work is `employee_info.hire_date`, one hop away and labelled
                        // "Eintrittsdatum"; calling this one that would put a wrong answer in the
                        // column an operator sorts a staff list by.
                        ExportColumn(EmployeeTable.createdAt, "Angelegt am", ExportFieldType.DATE),
                    ),
                // No aggregates *of* this table. `bill`, `kassenbuch_header` and `sales_invoice` all
                // reference `employees`, so a per-employee count or sum would be derivable — and
                // would be a per-head performance figure, which is a different thing from a staff
                // list. Nothing is offered rather than something misread.
                //
                // The flag below is the other half of that, and the half a subject rooted elsewhere
                // needs: an aggregate reached *through* this table is grouped by a person whatever
                // the export is called, so Kassenbelege and Kassenbuch withheld sixteen such cells
                // each the day it was set. See `ExportTable.aggregatesAreMonitoring`.
                aggregatesAreMonitoring = true,
            ),
            // The personnel record, registered **only** so a staff list can carry a name. `employees`
            // holds none: the name lives here, one to-one hop away.
            //
            // Four columns out of eighteen, and the sixteen-odd left out are the point of the entry:
            //  - `date_of_birth`, `salary`, `hourly_rate` — identity-grade and payroll data. Denied
            //    outright in [ForbiddenExportTargets.COLUMNS] as well as omitted here, on the same
            //    reasoning as `sales_invoice.tse_`: the allowlist protects them today, a denylist
            //    entry stops a future contributor adding them in good faith.
            //  - `street`, `city`, `state`, `zip_code`, `country` — private home addresses. Left out,
            //    not denied: an address list is a conceivable future request an operator could make
            //    deliberately, and this is a decision rather than a ban.
            //  - `email`, `phone_number` — private contact details, and a staff list does not need
            //    them to say who works here.
            //  - `contracted_hours`, `contracted_hours_period` — contract terms; remuneration-adjacent
            //    and never needed to answer this subject's question.
            //  - `created_at`, `updated_at`, `employee_id` — record plumbing and a bare id.
            ExportTable(
                table = EmployeeInfoTable,
                label = "Personaldaten",
                capability = Capability.EMPLOYEE_READ,
                columns =
                    listOf(
                        ExportColumn(EmployeeInfoTable.firstName, "Vorname"),
                        ExportColumn(EmployeeInfoTable.lastName, "Nachname"),
                        ExportColumn(EmployeeInfoTable.jobTitle, "Position"),
                        // A `date`, not a `datetime` — the renderer takes both, and this is the one
                        // column that answers "since when".
                        ExportColumn(EmployeeInfoTable.hireDate, "Eintrittsdatum", ExportFieldType.DATE),
                    ),
            ),
        )

    private val byTable: Map<Table, ExportTable> = entries.associateBy { it.table }

    /** The entry for [table], or null when it is not registered — which means not exportable at all. */
    fun entryFor(table: Table): ExportTable? = byTable[table]
}
