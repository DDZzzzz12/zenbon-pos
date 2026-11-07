package org.example.project.ui.screens.settings.components

/**
 * The server's pre-export check answers with English sentences (the wire contract), which showed up
 * unchanged in a German screen. This reads the four sentences it can produce back into their facts so
 * the screen can word them in its own language; any other text stays as the server sent it.
 */
internal sealed interface DsfinvkValidationMessage {
    data object NoZReports : DsfinvkValidationMessage

    data class MissingSignatures(
        val total: Int,
        val bills: Int,
        val orders: Int,
        val cashBookEntries: Int,
        val cancellations: Int,
    ) : DsfinvkValidationMessage

    data class MissingVatDetails(
        val bills: Int,
    ) : DsfinvkValidationMessage

    data class MissingTaxId(
        val zNrs: String,
    ) : DsfinvkValidationMessage

    companion object {
        private val signatures = Regex("""^(\d+) transactions are missing TSE signatures \((.*)\)$""")
        private val vat = Regex("""^(\d+) bills are missing VAT breakdown details$""")
        private val taxId = Regex("""^DSFinV-K export requires Steuernummer or USt-IdNr .* for Z_NR (.+?)\. A Kassenabschluss .*""")
        private val part = Regex("""(\d+) (bills|orders|cash book entries|cancellations)""")

        /** The recognised message, or null for anything else. */
        fun parse(raw: String): DsfinvkValidationMessage? {
            if (raw == "No Z-reports found in the specified date range") return NoZReports
            signatures.find(raw)?.let { m ->
                val counts = part.findAll(m.groupValues[2]).associate { it.groupValues[2] to it.groupValues[1].toInt() }
                return MissingSignatures(
                    total = m.groupValues[1].toInt(),
                    bills = counts["bills"] ?: 0,
                    orders = counts["orders"] ?: 0,
                    cashBookEntries = counts["cash book entries"] ?: 0,
                    cancellations = counts["cancellations"] ?: 0,
                )
            }
            vat.find(raw)?.let { return MissingVatDetails(it.groupValues[1].toInt()) }
            taxId.find(raw)?.let { return MissingTaxId(it.groupValues[1]) }
            return null
        }
    }
}
