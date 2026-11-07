package org.example.project.features.customexport

import kotlin.test.assertTrue

/**
 * The written file as the recipient's spreadsheet sees it: UTF-8, BOM skipped, CRLF-split.
 *
 * Shared by every custom-export test that reads produced bytes. A second copy would be a second
 * definition of "what the recipient sees", and the BOM assertion in particular is the kind of thing
 * one copy would quietly lose.
 */
internal fun ByteArray.asCsvLines(): List<String> {
    assertTrue(
        size >= 3 && this[0] == 0xEF.toByte() && this[1] == 0xBB.toByte() && this[2] == 0xBF.toByte(),
        "the file starts with a UTF-8 BOM, or Excel reads every umlaut as the system code page",
    )
    return drop(3)
        .toByteArray()
        .toString(Charsets.UTF_8)
        .split("\r\n")
        .filter { it.isNotEmpty() }
}
