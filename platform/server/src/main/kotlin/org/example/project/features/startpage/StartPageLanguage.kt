package org.example.project.features.startpage

/** The languages the start page speaks; German is the fallback (the product's primary language). */
enum class StartPageLanguage(
    val tag: String,
) {
    DE("de"),
    EN("en"),
    ZH("zh"),
    ;

    companion object {
        /** Parses an Accept-Language header value; ignores malformed parts instead of failing. */
        fun fromAcceptLanguage(header: String?): StartPageLanguage {
            if (header.isNullOrBlank()) return DE
            return header
                .split(',')
                .mapNotNull { part ->
                    val pieces = part.trim().split(';')
                    val rawTag = pieces.first().trim()
                    val primary = rawTag.substringBefore('-').lowercase()
                    if (primary.isEmpty()) return@mapNotNull null
                    val q = qualityOf(pieces.drop(1))
                    if (q <= 0.0) return@mapNotNull null
                    primary to q
                }.sortedByDescending { it.second }
                .firstNotNullOfOrNull { (primary, _) -> entries.firstOrNull { it.tag == primary } }
                ?: DE
        }

        /** q-value of the parameters after the tag: 1.0 if absent; 0.0 (not acceptable) if malformed, NaN or 0. */
        private fun qualityOf(params: List<String>): Double {
            val raw = params.map { it.trim() }.firstOrNull { it.startsWith("q=", ignoreCase = true) } ?: return 1.0
            val q = raw.substring(2).trim().toDoubleOrNull() ?: return 0.0
            return if (q.isNaN()) 0.0 else q
        }
    }
}
