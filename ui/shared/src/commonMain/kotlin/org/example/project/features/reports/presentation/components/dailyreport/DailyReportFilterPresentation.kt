package org.example.project.features.reports.presentation.components.dailyreport

/**
 * How the daily report shows its filters.
 *
 * [Scroller] is the till's layout and stays exactly as it was: on a phone a sideways chip scroller
 * above the view switch, wider a wrapping row of chips and the view switch.
 * [Panel] is the back office's Zenbon filter pattern: the period chip and a Filter button in one row,
 * employee / training / sort / payment type in `PosFilterPanel`, a result line, and the view switch on
 * its own line; on a phone the KPI cards collapse into a one-line summary.
 */
enum class DailyReportFilterPresentation {
    Scroller,
    Panel,
}
