package org.example.project.core.utils

/** True when a formatted amount shows no non-zero digit ("0,00 €", "0"). */
fun isZeroAmountDisplay(display: String): Boolean = display.none { it in '1'..'9' }
