package org.example.project.utils.formatter

/**
 * The BSI certification ID ("Zertifizierungs-ID") of the attached TSE, as reported in the
 * Kassenmeldung: `nnnn-yyyy`, or `nnnn-yyyy-MA-ZZ` for a maintenance certificate (AEAO zu § 146a
 * Nr. 1.16.2.2 as amended by the BMF letter of 3 Mar 2025). The caller adds the `BSI-K-TR-` prefix.
 *
 * The ID is read from the device rather than guessed from a product name. The Swissbit SDK's
 * `tseDescription()` is documented as "Same as `tseCertificationId`", and a real Swissbit USB TSE
 * (firmware 1.1.1) returns `BSI-K-TR-0563`, which has no year. The year is filled in from the BSI
 * list of certified TSEs. An ID whose year is unknown yields `null` instead of a guess, and the
 * operator enters it as `bsiCertIdOverride`.
 */
object BsiCertificationId {
    private val pattern =
        Regex("""BSI-K-TR-(\d{4})(?:-(\d{4}))?(-MA-\d{2})?""", RegexOption.IGNORE_CASE)

    /**
     * Issue years of the Swissbit certificates on the BSI list "Technische Sicherheitseinrichtungen
     * für elektronische Aufzeichnungssysteme", checked 2026-10-06. Only devices this system can drive
     * through the Swissbit WORM SDK are listed (TSE 1.x and TSE 2.0, USB/SD/microSD). There were no
     * `-MA-` certificates on that list.
     */
    private val swissbitIssueYears =
        mapOf(
            "0362" to "2019", // Swissbit TSE 1.0 (USB, SD, microSD)
            "0412" to "2020", // Swissbit TSE 1.0 (USB, SD, microSD)
            "0477" to "2021", // Swissbit USB TSE 1.0.4/1.1.0
            "0563" to "2023", // Swissbit USB TSE 1.1.1/1.1.0
            "0620" to "2025", // Swissbit TSE 1.1 recertification (USB, SD, microSD)
            "0800" to "2026", // Swissbit TSE 2.0 (Swissbit AG)
            "0802" to "2026", // Swissbit TSE 2.0 (Epson UK Ltd.)
        )

    /** Extracts the certification ID from [description], or `null` when it carries none we can complete. */
    fun fromTseDescription(description: String?): String? {
        val match = description?.let { pattern.find(it) } ?: return null
        val (number, reportedYear, maintenance) = match.destructured
        val year = reportedYear.ifEmpty { swissbitIssueYears[number] } ?: return null
        return "$number-$year${maintenance.uppercase()}"
    }
}
