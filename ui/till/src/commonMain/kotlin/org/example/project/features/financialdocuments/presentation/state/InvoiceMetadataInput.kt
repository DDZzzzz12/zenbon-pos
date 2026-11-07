/**
 * Lightweight data class to carry invoice metadata from the
 * CreateKassenbuchEntryPanel to the KassenbuchViewModel.
 *
 * Used when the user selects "Rechnung hochladen" (Upload Invoice)
 * and wants to attach metadata (vendor, amounts, DATEV AP fields)
 * alongside the PDF file.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.financialdocuments.presentation.state

/**
 * Invoice metadata input collected from the Kassenbuch entry creation dialog.
 *
 * All fields are optional strings; numeric conversions happen in the ViewModel
 * before passing to the API client.
 *
 * @property vendorName Supplier or company name
 * @property invoiceNumber Unique invoice reference number
 * @property invoiceDate Invoice issue date (YYYY-MM-DD format)
 * @property dueDate Payment due date (YYYY-MM-DD format)
 * @property netAmount Net amount before tax
 * @property grossAmount Gross amount including tax
 * @property taxAmount Total tax amount
 * @property taxRate Tax rate percentage
 * @property currency Currency code (e.g., EUR)
 * @property iban Bank IBAN for payment
 * @property paymentReference Payment reference / Verwendungszweck
 * @property supplierCity DATEV AP: Supplier city
 * @property vatId DATEV AP: VAT identification number (USt-IdNr.)
 * @property accountNo DATEV AP: G/L account number (Gegenkonto)
 * @property bpAccountNo DATEV AP: Creditor account number (Kreditorenkonto)
 * @property deliveryDate DATEV AP: Delivery/service date
 * @property bookingText DATEV AP: Booking text (Buchungstext)
 * @property invoiceType Invoice type (Rechnung, Gutschrift, etc.)
 * @property draweeNo Drawer number / Ziehernummer
 * @property orderId Order reference
 * @property supplierStreet Supplier street address
 * @property supplierZip Supplier ZIP/postal code
 * @property supplierCountry Supplier country
 * @property supplierPhone Supplier phone number
 * @property supplierEmail Supplier email address
 * @property shipToCountry Ship-to country
 * @property shipFromCountry Ship-from country
 * @property paymentConditionsText Payment conditions text
 * @property paidAt Paid date (ISO string)
 * @property paymentDunningBlock Payment dunning block flag
 * @property costCategoryId Cost category ID (Kost1)
 * @property costCategoryId2 Cost category ID 2 (Kost2)
 * @property costAmount Cost amount
 * @property exchangeRate Exchange rate
 * @property euTaxRate EU tax rate for cross-border transactions
 * @property buCode Business transaction key (BU-Schlüssel)
 * @property typeOfReceivable Type of receivable
 * @property deliveryDateStart Delivery period start date
 * @property deliveryDateEnd Delivery period end date
 */
data class InvoiceMetadataInput(
    val vendorName: String = "",
    val invoiceNumber: String = "",
    val invoiceDate: String = "",
    val dueDate: String = "",
    val netAmount: String = "",
    val grossAmount: String = "",
    val taxAmount: String = "",
    val taxRate: String = "",
    val currency: String = "",
    val iban: String = "",
    val paymentReference: String = "",
    val supplierCity: String = "",
    val vatId: String = "",
    val accountNo: String = "",
    val bpAccountNo: String = "",
    val deliveryDate: String = "",
    val bookingText: String = "",
    val invoiceType: String = "",
    val draweeNo: String = "",
    val orderId: String = "",
    val supplierStreet: String = "",
    val supplierZip: String = "",
    val supplierCountry: String = "",
    val supplierPhone: String = "",
    val supplierEmail: String = "",
    val shipToCountry: String = "",
    val shipFromCountry: String = "",
    val paymentConditionsText: String = "",
    val paidAt: String = "",
    val paymentDunningBlock: String = "false",
    val costCategoryId: String = "",
    val costCategoryId2: String = "",
    val costAmount: String = "",
    val exchangeRate: String = "",
    val euTaxRate: String = "",
    val buCode: String = "",
    val typeOfReceivable: String = "",
    val deliveryDateStart: String = "",
    val deliveryDateEnd: String = "",
)
