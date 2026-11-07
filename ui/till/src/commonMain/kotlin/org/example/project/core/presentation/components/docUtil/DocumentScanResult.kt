/**
 * Data class representing the result of a document scan operation.
 *
 * Carries the scanned PDF bytes alongside optional OCR-extracted metadata
 * that can be used to pre-fill upload form fields. All metadata fields are
 * nullable — when null, the corresponding form field is left unchanged.
 *
 * Used by [DocumentScannerButton] to pass scan results back to callers.
 *
 * @property pdfBytes Raw PDF file bytes from the scanner
 * @property fileName Generated filename for the scanned document
 * @property detectedFileType Detected document type (e.g. "INVOICE", "BANK_STATEMENT")
 * @property detectedDate Detected transaction date in ISO yyyy-MM-dd format
 * @property detectedAmount Detected monetary amount as decimal string (e.g. "1234.56")
 * @property detectedIban Detected IBAN (e.g. "DE89370400440532013000")
 * @property detectedAccountNumber Detected account number
 * @property detectedVendorName Detected vendor/company name (for invoices)
 * @property detectedInvoiceNumber Detected invoice number (for invoices)
 * @property detectedNetAmount Detected net amount before tax (for invoices)
 * @property detectedTaxAmount Detected tax amount (for invoices)
 * @property detectedTaxRate Detected tax rate percentage as string (for invoices)
 * @property detectedDueDate Detected due date in ISO yyyy-MM-dd format (for invoices)
 * @property detectedPaymentReference Detected payment reference (for invoices)
 * @property detectedSupplierCity Detected supplier city from address block (for invoices / DATEV AP)
 * @property detectedVatId Detected VAT identification number / USt-IdNr. (for invoices / DATEV AP)
 * @property detectedDeliveryDate Detected delivery/service date in ISO yyyy-MM-dd format (for invoices / DATEV AP)
 * @property detectedBankName Detected bank name (for bank statements)
 * @property detectedBic Detected BIC/SWIFT code (for bank statements)
 * @property detectedStatementNumber Detected statement number (for bank statements)
 * @property detectedPeriodStart Detected period start in ISO yyyy-MM-dd format (for bank statements)
 * @property detectedPeriodEnd Detected period end in ISO yyyy-MM-dd format (for bank statements)
 * @property detectedOpeningBalance Detected opening balance as decimal string (for bank statements)
 * @property detectedClosingBalance Detected closing balance as decimal string (for bank statements)
 * @property rawOcrText Full raw OCR text for server-side storage
 *
 * @since 2.0.0
 */
package org.example.project.core.presentation.components.docUtil

data class DocumentScanResult(
    val pdfBytes: ByteArray,
    val fileName: String,
    val detectedFileType: String? = null,
    val detectedDate: String? = null,
    val detectedAmount: String? = null,
    val detectedIban: String? = null,
    val detectedAccountNumber: String? = null,
    // Invoice-specific
    val detectedVendorName: String? = null,
    val detectedInvoiceNumber: String? = null,
    val detectedNetAmount: String? = null,
    val detectedTaxAmount: String? = null,
    val detectedTaxRate: String? = null,
    val detectedDueDate: String? = null,
    val detectedPaymentReference: String? = null,
    // DATEV AP invoice fields
    val detectedSupplierCity: String? = null,
    val detectedVatId: String? = null,
    val detectedDeliveryDate: String? = null,
    // Bank statement-specific
    val detectedBankName: String? = null,
    val detectedBic: String? = null,
    val detectedStatementNumber: String? = null,
    val detectedPeriodStart: String? = null,
    val detectedPeriodEnd: String? = null,
    val detectedOpeningBalance: String? = null,
    val detectedClosingBalance: String? = null,
    // Raw OCR text for storage
    val rawOcrText: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as DocumentScanResult
        if (!pdfBytes.contentEquals(other.pdfBytes)) return false
        if (fileName != other.fileName) return false
        if (detectedFileType != other.detectedFileType) return false
        if (detectedDate != other.detectedDate) return false
        if (detectedAmount != other.detectedAmount) return false
        if (detectedIban != other.detectedIban) return false
        if (detectedAccountNumber != other.detectedAccountNumber) return false
        if (detectedVendorName != other.detectedVendorName) return false
        if (detectedInvoiceNumber != other.detectedInvoiceNumber) return false
        if (detectedNetAmount != other.detectedNetAmount) return false
        if (detectedTaxAmount != other.detectedTaxAmount) return false
        if (detectedTaxRate != other.detectedTaxRate) return false
        if (detectedDueDate != other.detectedDueDate) return false
        if (detectedPaymentReference != other.detectedPaymentReference) return false
        if (detectedSupplierCity != other.detectedSupplierCity) return false
        if (detectedVatId != other.detectedVatId) return false
        if (detectedDeliveryDate != other.detectedDeliveryDate) return false
        if (detectedBankName != other.detectedBankName) return false
        if (detectedBic != other.detectedBic) return false
        if (detectedStatementNumber != other.detectedStatementNumber) return false
        if (detectedPeriodStart != other.detectedPeriodStart) return false
        if (detectedPeriodEnd != other.detectedPeriodEnd) return false
        if (detectedOpeningBalance != other.detectedOpeningBalance) return false
        if (detectedClosingBalance != other.detectedClosingBalance) return false
        if (rawOcrText != other.rawOcrText) return false
        return true
    }

    override fun hashCode(): Int {
        var result = pdfBytes.contentHashCode()
        result = 31 * result + fileName.hashCode()
        result = 31 * result + (detectedFileType?.hashCode() ?: 0)
        result = 31 * result + (detectedDate?.hashCode() ?: 0)
        result = 31 * result + (detectedAmount?.hashCode() ?: 0)
        result = 31 * result + (detectedIban?.hashCode() ?: 0)
        result = 31 * result + (detectedAccountNumber?.hashCode() ?: 0)
        result = 31 * result + (detectedVendorName?.hashCode() ?: 0)
        result = 31 * result + (detectedInvoiceNumber?.hashCode() ?: 0)
        result = 31 * result + (detectedNetAmount?.hashCode() ?: 0)
        result = 31 * result + (detectedTaxAmount?.hashCode() ?: 0)
        result = 31 * result + (detectedTaxRate?.hashCode() ?: 0)
        result = 31 * result + (detectedDueDate?.hashCode() ?: 0)
        result = 31 * result + (detectedPaymentReference?.hashCode() ?: 0)
        result = 31 * result + (detectedSupplierCity?.hashCode() ?: 0)
        result = 31 * result + (detectedVatId?.hashCode() ?: 0)
        result = 31 * result + (detectedDeliveryDate?.hashCode() ?: 0)
        result = 31 * result + (detectedBankName?.hashCode() ?: 0)
        result = 31 * result + (detectedBic?.hashCode() ?: 0)
        result = 31 * result + (detectedStatementNumber?.hashCode() ?: 0)
        result = 31 * result + (detectedPeriodStart?.hashCode() ?: 0)
        result = 31 * result + (detectedPeriodEnd?.hashCode() ?: 0)
        result = 31 * result + (detectedOpeningBalance?.hashCode() ?: 0)
        result = 31 * result + (detectedClosingBalance?.hashCode() ?: 0)
        result = 31 * result + (rawOcrText?.hashCode() ?: 0)
        return result
    }
}
