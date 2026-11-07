package org.example.project.core.utils

/** The one placeholder every screen shows for "no value". */
const val EMPTY_VALUE = "—"

/** This string, or [EMPTY_VALUE] when it is null or blank. */
fun String?.orDash(): String = if (isNullOrBlank()) EMPTY_VALUE else this
