package org.example.project.printer.core.formatter

/**
 * The printable lines of an ESC/POS job, control bytes stripped — what the paper shows.
 * The formatters default to CP850.
 */
internal fun ByteArray.printedLines(): List<String> =
    toString(charset("Cp850"))
        .replace(Regex("[\\u0000-\\u0009\\u000b-\\u001f\\u007f]"), "")
        .lines()
