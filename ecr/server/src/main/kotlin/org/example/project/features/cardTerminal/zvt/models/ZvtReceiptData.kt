/**
 * Receipt data model for ZVT print messages.
 *
 * The PT can send receipt data to the ECR via Print Line (06 D1)
 * or Print Text-Block (06 D3) commands. This model collects and
 * organizes the receipt content for processing or printing.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.models

/**
 * Receipt data collected from PT print messages.
 *
 * @property lines Individual text lines of the receipt
 * @property isMerchantCopy Whether this is the merchant copy (vs. customer copy)
 */
data class ZvtReceiptData(
    val lines: List<String> = emptyList(),
    val isMerchantCopy: Boolean = false,
) {
    /**
     * Returns the full receipt as a single string with newline separators.
     */
    fun toFullText(): String = lines.joinToString("\n")

    /**
     * Returns true if the receipt has any content.
     */
    fun isNotEmpty(): Boolean = lines.isNotEmpty()
}
