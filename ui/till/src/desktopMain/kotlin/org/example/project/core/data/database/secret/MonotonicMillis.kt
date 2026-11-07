package org.example.project.core.data.database.secret

/** Milliseconds from an arbitrary origin that never go backwards, unlike the wall clock. Only for measuring time windows. */
internal fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L
