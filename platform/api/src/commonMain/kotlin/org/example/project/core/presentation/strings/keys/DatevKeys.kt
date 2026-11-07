package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey

/**
 * String keys for the DATEV automatic export controls (per-type cadences, master enabled toggle)
 * shown in the DATEV XML configuration section.
 *
 * `RunHour`, `RunNow`, `LastRunAt`, `NextRunAt`, `Status`, `JobEnabled`, `JobDisabled` are
 * intentionally reused from [BackupKeys] — semantically identical for any scheduled job.
 */
object DatevKeys {
    val AutoExportSection = StringKey("datev.auto_export_section")
    val AutoExportEnabled = StringKey("datev.auto_export_enabled")
    val KassenbuchCadence = StringKey("datev.kassenbuch_cadence")
    val ApLedgerCadence = StringKey("datev.ap_ledger_cadence")
    val BuchungsstapelCadence = StringKey("datev.buchungsstapel_cadence")
    val NoCadenceHint = StringKey("datev.no_cadence_hint")
    val CadenceOff = StringKey("datev.cadence_off")
    val CadenceWeekly = StringKey("datev.cadence_weekly")
    val CadenceMonthly = StringKey("datev.cadence_monthly")
}
