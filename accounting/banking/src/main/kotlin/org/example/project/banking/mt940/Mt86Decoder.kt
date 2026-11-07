package org.example.project.banking.mt940

/**
 * Reads the German `:86:` field: a Deutsche Kreditwirtschaft GVC followed by `?00`–`?63` subfields,
 * with SEPA tags packed into the remittance lines.
 *
 * ### Fallback is structural, not incidental
 * If the field does not match the GVC-plus-`?` shape, **no decoding is attempted at all**: the raw
 * text becomes the remittance and every counterparty field stays null. A partially-recognised field
 * is treated as unrecognised. This is what guarantees that a non-German or non-conforming bank
 * degrades to opaque text rather than yielding a confidently wrong counterparty — and a wrong
 * counterparty is indistinguishable from a right one to whoever reads it later.
 *
 * ### Nothing is joined with a separator
 * The remittance subfields are concatenated exactly as they arrived. Banks wrap them at a fixed
 * width, cutting IBANs and references mid-token, so inserting a space would corrupt precisely the
 * values that matter most.
 *
 * The GVC has no home in the domain model and is not written to one; it travels in `rawEntry`.
 */
object Mt86Decoder {
    private val STRUCTURED = Regex("""^(\d{3})\?\d{2}""")

    /** Subfields whose contents form the remittance, in the order the DK specifies. */
    private val REMITTANCE_KEYS = ((20..29) + (60..63)).map { it.toString().padStart(2, '0') }.toSet()

    /**
     * Listed in the order the DK specifies them, and that order is load-bearing — see
     * [extractSepaTags].
     */
    private val SEPA_TAGS = listOf("EREF+", "KREF+", "MREF+", "CRED+", "DEBT+", "SVWZ+", "ABWA+", "ABWE+")

    fun decode(raw: String): Mt86Fields {
        val field = raw.trim()
        if (field.isEmpty()) return Mt86Fields()
        if (!STRUCTURED.containsMatchIn(field)) {
            // Unrecognised shape: keep the text whole and claim nothing about it.
            return Mt86Fields(remittance = field)
        }

        val gvc = field.take(3)
        val subfields = splitSubfields(field.drop(3))

        val remittanceRaw = REMITTANCE_KEYS.sorted().mapNotNull { subfields[it] }.joinToString("")
        val sepa = extractSepaTags(remittanceRaw)

        return Mt86Fields(
            gvc = gvc,
            postingText = subfields["00"]?.ifBlank { null },
            remittance = (sepa["SVWZ+"] ?: remittanceRaw).ifBlank { null },
            counterpartyName =
                listOfNotNull(subfields["32"], subfields["33"])
                    .joinToString("")
                    .trim()
                    .ifBlank { null },
            counterpartyIban = subfields["31"]?.trim()?.ifBlank { null },
            counterpartyBic = subfields["30"]?.trim()?.ifBlank { null },
            endToEndId = sepa["EREF+"],
            mandateId = sepa["MREF+"],
            creditorId = sepa["CRED+"],
        )
    }

    /**
     * Splits on `?NN`. A key repeated by a bank keeps its first occurrence — the DK does not permit
     * repeats, and overwriting would silently prefer the last one.
     */
    private fun splitSubfields(body: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        Regex("""\?(\d{2})([^?]*)""").findAll(body).forEach { match ->
            result.putIfAbsent(match.groupValues[1], match.groupValues[2])
        }
        return result
    }

    /**
     * Each tag's value runs until the next recognised tag, and tags are recognised **in the DK's
     * canonical order only** — a tag may not appear before one already matched.
     *
     * That ordering rule is what stops free text fabricating a reference. The tags before `SVWZ+`
     * carry structured references and no free text, so matching them as bare substrings is safe.
     * `SVWZ+`'s value *is* free text, and a remittance reading `"Zahlung XEREF+ABC"` would otherwise
     * be cut at `EREF+`, inventing an end-to-end ID that the bank never sent and truncating the
     * purpose an operator needs to read. Once `SVWZ+` has matched, only the tags the DK places after
     * it (`ABWA+`, `ABWE+`) can terminate its value.
     *
     * A boundary rule — requiring whitespace or a non-alphanumeric before the tag — would be the
     * obvious fix and is the wrong one. `?2x` subfields are wrapped at a fixed width and rejoined
     * here with no separator, so a genuine `EREF+` routinely lands flush against the last character
     * of the previous line. Enforcing a boundary would drop real references to avoid a rare
     * fabricated one, trading a common silent loss for an uncommon silent error.
     *
     * The order rule also makes repeats first-wins, matching [splitSubfields]: a second `SVWZ+`
     * cannot match, because its rank is no longer greater than the last matched one.
     *
     * Residual risk, accepted: free text containing a literal `ABWA+` or `ABWE+` after `SVWZ+` still
     * truncates. Those tags are far rarer in prose than `EREF+`, and the corpus in Task 21 is where
     * a real counter-example would show up.
     */
    private fun extractSepaTags(text: String): Map<String, String> {
        val hits = mutableListOf<Pair<String, Int>>()
        var searchFrom = 0
        var minRank = 0

        while (minRank < SEPA_TAGS.size) {
            val next =
                SEPA_TAGS
                    .withIndex()
                    .filter { it.index >= minRank }
                    .mapNotNull { (rank, tag) ->
                        text.indexOf(tag, searchFrom).takeIf { it >= 0 }?.let { Triple(tag, it, rank) }
                    }.minByOrNull { it.second } ?: break

            hits += next.first to next.second
            searchFrom = next.second + next.first.length
            minRank = next.third + 1
        }
        if (hits.isEmpty()) return emptyMap()

        return hits
            .mapIndexed { index, (tag, start) ->
                val valueStart = start + tag.length
                val valueEnd = hits.getOrNull(index + 1)?.second ?: text.length
                tag to text.substring(valueStart, valueEnd).trim()
            }.toMap()
    }
}
