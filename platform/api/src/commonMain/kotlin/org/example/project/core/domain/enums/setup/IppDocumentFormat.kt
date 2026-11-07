/**
 * IPP document format enum for distinguishing thermal IPP from standard IPP printers.
 *
 * Determines the MIME type and data format used when sending print jobs via IPP protocol:
 * - [ESC_POS]: Raw ESC/POS byte commands for thermal receipt printers (application/octet-stream)
 * - [PDF]: PDF documents for standard office/laser printers (application/pdf)
 *
 * Stored in the printer_setup table's ipp_document_format column.
 *
 * @since 1.0.0
 */
package org.example.project.core.domain.enums.setup

/**
 * Document format for IPP printing.
 *
 * Controls how print data is generated and sent to IPP printers.
 */
enum class IppDocumentFormat {
    /** ESC/POS byte commands for thermal receipt printers */
    ESC_POS,

    /** PDF format for standard office printers */
    PDF,
}
