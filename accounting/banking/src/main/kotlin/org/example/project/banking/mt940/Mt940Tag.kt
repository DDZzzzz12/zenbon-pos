package org.example.project.banking.mt940

/**
 * One SWIFT field: its identifier without the delimiting colons, and everything that followed it up
 * to the next tag.
 *
 * Two views of the content, because the two tags that matter want different ones:
 *
 * - [value] has continuation lines rejoined with no separator. `:86:` needs exactly this — banks
 *   wrap its `?2x` subfields at a fixed width and cut values mid-token.
 * - [firstLine] is the tag's own line alone. `:61:` needs this: SWIFT allows an optional
 *   *supplementary details* subfield on a **second** line, and under the no-separator join that text
 *   would be glued onto the bank reference and stored as a wrong `accountServicerReference` — a
 *   silently corrupt value rather than a clean refusal.
 */
data class Mt940Tag(
    val id: String,
    val value: String,
    val firstLine: String = value,
)
