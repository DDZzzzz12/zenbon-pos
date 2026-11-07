package org.example.project.features.bank.data.service

/**
 * Counters from one [StatementAccountResolver] sweep.
 *
 * [unknownIbans] is distinct and drives the "add this account?" prompt — it is the only output that
 * needs a human.
 */
data class StatementResolutionReport(
    val resolved: Int,
    val withoutIban: Int,
    val unknownIbans: List<String>,
)
