package org.example.project.admin.features.finance.presentation

import kotlinx.datetime.LocalDate

/**
 * The typed Buchungsbeginn as an ISO `yyyy-MM-dd` string, or null when it must not be sent.
 *
 * The server answers 400 for anything else; this half exists so *Speichern* is already disabled
 * before the operator makes the request (root `CLAUDE.md` rule 8). Parsing — rather than a length
 * or regex check — is what rejects `2026-02-30`, a date that looks perfectly well-formed.
 *
 * Surrounding whitespace is forgiven, because it is a typing accident rather than a wrong date.
 */
fun isoDateOrNull(typed: String): String? = runCatching { LocalDate.parse(typed.trim()) }.getOrNull()?.toString()
