package org.example.project.ui.components

/**
 * Minimal Kotlin source scanner for [NoNestedScrollInPosDialogTest]: finds the trailing-lambda
 * body of every `PosDialog(…) { … }` call site. Offsets are preserved, so a hit maps back to a line
 * of the original file.
 */
internal object PosDialogScan {
    private val call = Regex("""(?<![\w.])PosDialog\(""")

    /**
     * Replaces the contents of string literals (plain and raw), character literals and comments
     * with spaces of the same length, keeping newlines, so brace matching and token search see
     * code only.
     */
    fun stripCommentsAndStrings(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0

        fun blank(
            from: Int,
            toExclusive: Int,
        ) {
            for (k in from until toExclusive) out.append(if (source[k] == '\n') '\n' else ' ')
        }

        while (i < source.length) {
            val c = source[i]
            when {
                source.startsWith("\"\"\"", i) -> {
                    val end = source.indexOf("\"\"\"", i + 3).let { if (it < 0) source.length else it + 3 }
                    blank(i, end)
                    i = end
                }
                c == '"' -> {
                    var j = i + 1
                    while (j < source.length && source[j] != '"' && source[j] != '\n') {
                        if (source[j] == '\\') j++
                        j++
                    }
                    val end = minOf(j + 1, source.length)
                    blank(i, end)
                    i = end
                }
                c == '\'' -> {
                    var j = i + 1
                    while (j < source.length && source[j] != '\'' && source[j] != '\n') {
                        if (source[j] == '\\') j++
                        j++
                    }
                    val end = minOf(j + 1, source.length)
                    blank(i, end)
                    i = end
                }
                source.startsWith("//", i) -> {
                    val end = source.indexOf('\n', i).let { if (it < 0) source.length else it }
                    blank(i, end)
                    i = end
                }
                source.startsWith("/*", i) -> {
                    val end = source.indexOf("*/", i + 2).let { if (it < 0) source.length else it + 2 }
                    blank(i, end)
                    i = end
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** Body ranges (inside the braces) of every `PosDialog(…) { … }` trailing lambda in [code]. */
    fun contentBodies(code: String): List<IntRange> =
        call
            .findAll(code)
            // `fun PosDialog(` is the definition, not a call.
            .filterNot { m -> code.substring(0, m.range.first).trimEnd().endsWith("fun") }
            .mapNotNull { m ->
                val open = m.range.last // index of '('
                val close = matching(code, open, '(', ')') ?: return@mapNotNull null
                var k = close + 1
                while (k < code.length && code[k].isWhitespace()) k++
                if (k >= code.length || code[k] != '{') return@mapNotNull null
                val end = matching(code, k, '{', '}') ?: return@mapNotNull null
                (k + 1) until end
            }.toList()

    fun indicesOf(
        code: String,
        token: String,
        within: IntRange,
    ): List<Int> {
        val hits = mutableListOf<Int>()
        var from = within.first
        while (true) {
            val idx = code.indexOf(token, from)
            if (idx < 0 || idx > within.last) return hits
            hits += idx
            from = idx + token.length
        }
    }

    fun lineOf(
        code: String,
        index: Int,
    ): Int = code.substring(0, index).count { it == '\n' } + 1

    private fun matching(
        code: String,
        openIndex: Int,
        open: Char,
        close: Char,
    ): Int? {
        var depth = 0
        for (i in openIndex until code.length) {
            when (code[i]) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }
}
