package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey

/** Localization keys for TSE export log-sequence plausibility verification (AGD §5.2 / OE.Transaction). */
object TseVerificationKeys {
    val verifyExport = StringKey("tse_verification.verify_export")
    val verifying = StringKey("tse_verification.verifying")
    val verdictPass = StringKey("tse_verification.verdict_pass")
    val verdictWarnings = StringKey("tse_verification.verdict_warnings")
    val verdictFail = StringKey("tse_verification.verdict_fail")
    val findingsTitle = StringKey("tse_verification.findings_title")
    val noExportSelected = StringKey("tse_verification.no_export_selected")
    val summary = StringKey("tse_verification.summary") // "{0} records, {1} transactions"
}
