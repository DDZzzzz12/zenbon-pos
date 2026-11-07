package org.example.project.features.financialdocuments.presentation.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.example.project.core.presentation.components.docUtil.DocumentScanResult

/**
 * Compose-observable state holder for the invoice metadata sub-form
 * shown inside [CreateKassenbuchEntryFormState] when the user uploads an invoice.
 */
@Stable
class InvoiceMetadataFormState {
    var vendorName: String by mutableStateOf("")
    var invoiceNumber: String by mutableStateOf("")
    var invoiceDate: String by mutableStateOf("")
    var dueDate: String by mutableStateOf("")
    var netAmount: String by mutableStateOf("")
    var grossAmount: String by mutableStateOf("")
    var taxAmount: String by mutableStateOf("")
    var taxRate: String by mutableStateOf("")
    var currency: String by mutableStateOf("")
    var iban: String by mutableStateOf("")
    var paymentReference: String by mutableStateOf("")
    var supplierCity: String by mutableStateOf("")
    var vatId: String by mutableStateOf("")
    var accountNo: String by mutableStateOf("")
    var bpAccountNo: String by mutableStateOf("")
    var deliveryDate: String by mutableStateOf("")
    var bookingText: String by mutableStateOf("")

    val isDirty: Boolean
        get() =
            vendorName.isNotBlank() ||
                invoiceNumber.isNotBlank() ||
                invoiceDate.isNotBlank() ||
                dueDate.isNotBlank() ||
                netAmount.isNotBlank() ||
                grossAmount.isNotBlank() ||
                taxAmount.isNotBlank() ||
                taxRate.isNotBlank() ||
                currency.isNotBlank() ||
                iban.isNotBlank() ||
                paymentReference.isNotBlank() ||
                supplierCity.isNotBlank() ||
                vatId.isNotBlank() ||
                accountNo.isNotBlank() ||
                bpAccountNo.isNotBlank() ||
                deliveryDate.isNotBlank() ||
                bookingText.isNotBlank()

    fun reset() {
        vendorName = ""
        invoiceNumber = ""
        invoiceDate = ""
        dueDate = ""
        netAmount = ""
        grossAmount = ""
        taxAmount = ""
        taxRate = ""
        currency = ""
        iban = ""
        paymentReference = ""
        supplierCity = ""
        vatId = ""
        accountNo = ""
        bpAccountNo = ""
        deliveryDate = ""
        bookingText = ""
    }

    /**
     * Apply the detected fields carried by a document scan; only non-null fields overwrite.
     *
     * The Android scanner currently returns the PDF bytes and no detected fields, so in practice
     * this leaves the form untouched — it stays because [DocumentScanResult] still models them.
     */
    fun applyScanResult(scan: DocumentScanResult) {
        scan.detectedVendorName?.let { vendorName = it }
        scan.detectedInvoiceNumber?.let { invoiceNumber = it }
        scan.detectedDate?.let { invoiceDate = it }
        scan.detectedDueDate?.let { dueDate = it }
        scan.detectedNetAmount?.let { netAmount = it }
        scan.detectedAmount?.let { grossAmount = it }
        scan.detectedTaxAmount?.let { taxAmount = it }
        scan.detectedTaxRate?.let { taxRate = it }
        scan.detectedIban?.let { iban = it }
        scan.detectedPaymentReference?.let { paymentReference = it }
        scan.detectedSupplierCity?.let { supplierCity = it }
        scan.detectedVatId?.let { vatId = it }
        scan.detectedDeliveryDate?.let { deliveryDate = it }
    }

    /** Returns true if at least vendor name or invoice number was detected — used to auto-expand the panel. */
    fun hasMeaningfulScanData(scan: DocumentScanResult): Boolean = scan.detectedVendorName != null || scan.detectedInvoiceNumber != null

    fun toInput(): InvoiceMetadataInput =
        InvoiceMetadataInput(
            vendorName = vendorName.trim(),
            invoiceNumber = invoiceNumber.trim(),
            invoiceDate = invoiceDate.trim(),
            dueDate = dueDate.trim(),
            netAmount = netAmount.trim(),
            grossAmount = grossAmount.trim(),
            taxAmount = taxAmount.trim(),
            taxRate = taxRate.trim(),
            currency = currency.trim(),
            iban = iban.trim(),
            paymentReference = paymentReference.trim(),
            supplierCity = supplierCity.trim(),
            vatId = vatId.trim(),
            accountNo = accountNo.trim(),
            bpAccountNo = bpAccountNo.trim(),
            deliveryDate = deliveryDate.trim(),
            bookingText = bookingText.trim(),
        )
}
