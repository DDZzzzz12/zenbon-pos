package org.example.project.utils

/**
 * Parses a comma-separated id list query parameter (e.g. `cashBookIds=1, 2,x,3`) leniently: blanks
 * around an entry are trimmed, entries that are not a `Long` are dropped, and a missing parameter or
 * one with no valid entry yields `null`, which the services read as "no filter, all books" (the
 * DATEV export default, which must never silently narrow).
 *
 * One definition for the Kassenbuch search (`:fiscal-server`) and the Kassenbuch DATEV exports
 * (`:accounting-server`), which carried identical private copies until the B2-4 open items.
 */
fun String?.toIdListOrNull(): List<Long>? =
    this
        ?.split(",")
        ?.mapNotNull { it.trim().toLongOrNull() }
        ?.takeIf { it.isNotEmpty() }
