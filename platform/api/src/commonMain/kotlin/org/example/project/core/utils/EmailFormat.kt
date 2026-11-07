package org.example.project.core.utils

/**
 * Shape check for an e-mail address the operator types into a master-data form (Debitor, Lieferant).
 *
 * Deliberately pragmatic, not RFC 5322: exactly one `@`, a non-empty local part, a domain that holds a
 * dot with something on both sides of it, and no whitespace anywhere. It catches what an operator
 * actually gets wrong (`kaputt`, `info@firma`, `info @firma.de`, a pasted name) and lets every real
 * address through, including `+tags`, sub-domains and IDN domains. Whether the mailbox exists is not
 * this check's business.
 *
 * One function for both levels (CLAUDE.md rule 8): the adminApp forms flag the field on Save with it,
 * and the debtor/supplier request DTOs' `validate()` — which the server services call — reject with it.
 */
object EmailFormat {
    /**
     * True when [value] is absent, blank, or a plausible address. The e-mail field is optional, so
     * "nothing entered" is valid; surrounding whitespace is ignored, as the writers trim it.
     */
    fun isAcceptable(value: String?): Boolean {
        val trimmed = value?.trim().orEmpty()
        return trimmed.isEmpty() || isPlausible(trimmed)
    }

    /** True when [address] (already trimmed) has the shape described on [EmailFormat]. */
    fun isPlausible(address: String): Boolean {
        if (address.isEmpty() || address.any { it.isWhitespace() }) return false
        val at = address.indexOf('@')
        if (at <= 0 || at != address.lastIndexOf('@')) return false
        val domain = address.substring(at + 1)
        val dot = domain.lastIndexOf('.')
        return dot > 0 && dot < domain.length - 1 && !domain.startsWith('.') && !domain.contains("..")
    }
}
