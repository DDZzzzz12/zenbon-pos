package org.example.project.core.utils

/**
 * A Kassenbuch booking text as a person reads it. The stored text of a tip payout starts with the
 * technical tag `TA:` (it is part of the DATEV and DSFinV-K export and stays as it is); on screen the tag
 * only read as noise before "Trinkgeld-Auszahlung".
 */
fun String.shownBookingText(): String = removePrefix(TIP_PAYOUT_TAG)

private const val TIP_PAYOUT_TAG = "TA:"
