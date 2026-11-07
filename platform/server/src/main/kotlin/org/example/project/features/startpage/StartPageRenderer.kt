package org.example.project.features.startpage

import org.example.project.core.domain.enums.setup.StartPageTarget

/** Renders the start page as one self-contained HTML document: neutral styling, no script, every value escaped. */
object StartPageRenderer {
    data class Model(
        val shopName: String?,
        val version: String,
        val entries: List<WebEntry>,
        val browserDefault: StartPageTarget?,
        val serverDefault: StartPageTarget,
        val defaultUnavailable: Boolean,
        val language: StartPageLanguage,
    )

    fun render(model: Model): String {
        val strings = StartPageStrings.of(model.language)

        // Substitute first, then escape the whole result.
        fun text(
            key: String,
            arg: String? = null,
        ): String {
            val raw = strings.getValue(key)
            return escape(if (arg == null) raw else raw.replace("{0}", arg))
        }

        // Straight from the target, not from the entries: a default that is currently unavailable
        // (e.g. server default "pos" with browser stations off) has no entry but must still be named.
        fun labelOf(target: StartPageTarget): String =
            when (target) {
                StartPageTarget.ADMIN -> strings.getValue(StartPageStrings.ADMIN)
                StartPageTarget.POS -> strings.getValue(StartPageStrings.POS_BOTH)
                StartPageTarget.NONE -> target.wire
            }

        val title = model.shopName?.takeIf { it.isNotBlank() }?.let(::escape) ?: text(StartPageStrings.TITLE)
        val note =
            if (model.defaultUnavailable) {
                """<p class="note">${text(StartPageStrings.DEFAULT_UNAVAILABLE)}</p>"""
            } else {
                ""
            }
        val items = model.entries.joinToString("\n") { entryItem(it, ::text) }
        val active = activeDefault(model, ::text, ::labelOf)

        return """<!doctype html>
<html lang="${model.language.tag}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>$title</title>
<style>
$STYLE
</style>
</head>
<body><main>
<h1>$title</h1>
$note
<ul>
$items
</ul>
$active
<p class="version">${text(StartPageStrings.VERSION, model.version)}</p>
</main></body>
</html>
"""
    }

    private fun entryItem(
        entry: WebEntry,
        text: (String, String?) -> String,
    ): String {
        val hint = entry.hintKey?.let { """<p class="hint">${text(it, null)}</p>""" }.orEmpty()
        val makeDefault =
            entry.target
                ?.let { targetForm(it.wire, text(StartPageStrings.SET_DEFAULT, null), "") }
                .orEmpty()
        return """<li><a href="${escape(entry.path)}">${text(entry.labelKey, null)}</a>$hint$makeDefault</li>"""
    }

    private fun activeDefault(
        model: Model,
        text: (String, String?) -> String,
        labelOf: (StartPageTarget) -> String,
    ): String {
        val browser = model.browserDefault
        return when {
            browser != null && browser != StartPageTarget.NONE -> {
                val reset = targetForm(StartPageTarget.NONE.wire, text(StartPageStrings.RESET, null), " inline")
                """<p class="default">${text(StartPageStrings.DEFAULT_BROWSER, labelOf(browser))} · $reset</p>"""
            }
            model.serverDefault != StartPageTarget.NONE ->
                """<p class="default">${text(StartPageStrings.DEFAULT_SERVER, labelOf(model.serverDefault))}</p>"""
            else -> ""
        }
    }

    /** [wire] is an enum constant and [label] is already escaped. */
    private fun targetForm(
        wire: String,
        label: String,
        extraClass: String,
    ): String {
        val cls = if (extraClass.isEmpty()) "" else """ class="${extraClass.trim()}""""
        return """<form$cls method="post" action="/start-page">""" +
            """<input type="hidden" name="target" value="$wire">""" +
            """<button type="submit">$label</button></form>"""
    }

    /** HTML-escapes text and attribute values. */
    fun escape(value: String): String =
        buildString(value.length) {
            value.forEach {
                when (it) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> append(it)
                }
            }
        }

    private val STYLE =
        """
        :root{color-scheme:light dark;--bg:#fff;--fg:#1b1b1f;--muted:#5f6368;--card:#f4f5f7;--accent:#2f5bd3}
        @media (prefers-color-scheme:dark){:root{--bg:#121316;--fg:#e6e6ea;--muted:#a0a4ab;--card:#1d1f24;--accent:#8ab0ff}}
        body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,"Noto Sans",sans-serif}
        main{max-width:560px;margin:0 auto;padding:32px 16px}
        h1{font-size:1.5rem;margin:0 0 24px}
        ul{list-style:none;padding:0;margin:0;display:grid;gap:12px}
        li{background:var(--card);border-radius:12px;padding:16px}
        a{color:var(--accent);font-weight:600;font-size:1.1rem;text-decoration:none}
        .hint,.version,.default,.note{color:var(--muted);font-size:.9rem;margin:6px 0 0}
        .note{color:inherit}
        form{margin:8px 0 0}form.inline{display:inline;margin:0}
        button{font:inherit;font-size:.85rem;border:1px solid var(--muted);background:transparent;color:inherit;border-radius:8px;padding:4px 10px;cursor:pointer}
        """.trimIndent()
}
