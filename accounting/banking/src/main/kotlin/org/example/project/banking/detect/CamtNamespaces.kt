package org.example.project.banking.detect

import javax.xml.namespace.NamespaceContext

/**
 * Recognises the camt.053 message family and binds a prefix to whichever version a bank actually
 * sent.
 *
 * German banks are split across `camt.053.001.02` (the long-standing DK profile) and
 * `camt.053.001.08`, and `.04` is in the field too. Rather than enumerate versions, any
 * `camt.053.001.*` is accepted: the containing structure this parser walks — `Stmt`, `Bal`, `Ntry`,
 * `TxDtls` — is stable across them. Pinning to a list would reject a conformant file for no benefit.
 *
 * The leaf paths are **not** all stable, and accepting a version is not the same as reading it
 * correctly. `.04` wrapped the related parties in `Party40Choice` (`Dbtr/Nm` became `Dbtr/Pty/Nm`)
 * and renamed `BIC` to `BICFI`; `Camt053StatementMapper` therefore reads both shapes. A future
 * version that moves a leaf again will parse and reconcile while silently returning nulls, because
 * a missing counterparty breaks no arithmetic. Adding a version to the corpus is what catches that
 * — the mapper's version tolerance is a claim the corpus has to keep honest.
 *
 * `camt.052` (interim report) and `camt.054` (debit/credit notification) are deliberately **not**
 * accepted. They are different messages that happen to share much of the entry structure, so a
 * lenient match would half-parse one into something that looks like a statement but has no
 * meaningful opening or closing balance.
 *
 * Binding is by URI. The prefix a sender chose is irrelevant, so XPath expressions use the fixed
 * `camt:` prefix bound here to the document's own namespace.
 */
object CamtNamespaces {
    const val PREFIX = "camt"

    private val STATEMENT = Regex("""^urn:iso:std:iso:20022:tech:xsd:camt\.053\.001\.\d{2}$""")

    /** Returns [uri] when it is a camt.053 namespace, otherwise null. */
    fun statementNamespaceOrNull(uri: String?): String? = uri?.takeIf { STATEMENT.matches(it) }

    /** A context binding [PREFIX] to [namespaceUri] — the namespace the document itself declared. */
    fun contextFor(namespaceUri: String): NamespaceContext =
        object : NamespaceContext {
            override fun getNamespaceURI(prefix: String?): String = if (prefix == PREFIX) namespaceUri else ""

            override fun getPrefix(namespaceURI: String?): String? = if (namespaceURI == namespaceUri) PREFIX else null

            override fun getPrefixes(namespaceURI: String?): MutableIterator<String> = listOfNotNull(getPrefix(namespaceURI)).toMutableList().iterator()
        }
}
