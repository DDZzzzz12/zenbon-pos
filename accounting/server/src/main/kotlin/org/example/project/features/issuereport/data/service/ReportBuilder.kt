package org.example.project.features.issuereport.data.service

import org.example.project.features.issuereport.domain.models.ReportContext

/**
 * The title and body exactly as they will be posted. Pure; the preview builds once and the send
 * posts that stored snapshot (see `PreviewCache`), so nothing here has to be re-run identically.
 *
 * Operator and client text (title, description, screen, shop name) is scrubbed first and then has
 * every `@` followed by a zero-width space, so `@user` / `@org/team` cannot notify anyone on GitHub.
 * Scrubbing runs first because the e-mail pattern needs the bare `@`. The log excerpt is fenced as
 * code, where GitHub does not resolve mentions, so it is only scrubbed and left otherwise verbatim.
 */
object ReportBuilder {
    data class Built(
        val title: String,
        val body: String,
    )

    fun build(
        title: String,
        description: String,
        context: ReportContext,
        logExcerpt: String?,
    ): Built {
        val body =
            buildString {
                append(operatorText(description.trim()))
                append("\n\n---\n**Context** (added automatically)\n")
                append("- App version: ${context.appVersion}\n")
                append("- Platform: ${context.platform}\n")
                append("- Reported from: ${context.source.name}\n")
                context.screen?.takeIf { it.isNotBlank() }?.let { append("- Screen: ${operatorText(it)}\n") }
                append("- Time: ${context.timestamp}\n")
                context.shopName?.takeIf { it.isNotBlank() }?.let { append("- Shop: ${operatorText(it)}\n") }
                append("- Reporter role: ${context.reporterRole}\n")
                if (logExcerpt != null) {
                    append("\n**Server log (scrubbed excerpt)**\n```\n")
                    append(ReportScrubber.scrub(logExcerpt).replace("```", "'''"))
                    append("\n```\n")
                }
            }
        return Built(title = operatorText(title.trim()), body = body)
    }

    private fun operatorText(text: String): String = neutraliseMentions(ReportScrubber.scrub(text))

    private fun neutraliseMentions(text: String): String = text.replace("@", "@$ZERO_WIDTH_SPACE")

    private const val ZERO_WIDTH_SPACE = '\u200B'
}
