package org.example.project.features.maintenance

import at.favre.lib.crypto.bcrypt.BCrypt
import java.io.PrintStream
import java.nio.CharBuffer

/**
 * `java -jar pos-server.jar --hash-maintainer-password`: reads the password (no echo when a console
 * is attached, otherwise two lines from stdin), prints a ready-to-paste, single-quoted
 * `MAINTAINER_PASSWORD_HASH='…'` line and exits without starting the server. A BCrypt hash contains
 * `$`, so the quoting is the point. The password is never logged, printed or stored.
 */
object MaintainerPasswordHashCli {
    const val FLAG = "--hash-maintainer-password"
    private const val MIN_LENGTH = 12

    /** BCrypt only uses the first 72 bytes, and the server's verifier rejects anything longer. */
    private const val MAX_BYTES = 72
    private const val COST = 12

    fun run(
        prompt: (String) -> CharArray?,
        out: PrintStream,
        err: PrintStream,
    ): Int {
        val first = prompt("Maintainer password: ") ?: return fail(err, "No password was provided.")
        var second: CharArray? = null
        try {
            second = prompt("Repeat password: ") ?: return fail(err, "No password was provided.")
            if (first.size < MIN_LENGTH) return fail(err, "The password must be at least $MIN_LENGTH characters long.")
            if (utf8ByteLength(first) > MAX_BYTES) {
                return fail(err, "The password must be at most $MAX_BYTES bytes long (UTF-8); BCrypt ignores anything beyond that.")
            }
            if (!first.contentEquals(second)) return fail(err, "The two entries differ.")
            out.println("MAINTAINER_PASSWORD_HASH='${BCrypt.withDefaults().hashToString(COST, first)}'")
            err.println("Add that line to the server's environment together with MAINTAINER_USERNAME=<name>, then restart.")
            return 0
        } finally {
            first.fill('\u0000')
            second?.fill('\u0000')
        }
    }

    fun runInteractive(): Int {
        val console = System.console()
        val prompt: (String) -> CharArray? =
            if (console != null) {
                { text -> console.readPassword(text) }
            } else {
                { text ->
                    System.err.print(text)
                    readlnOrNull()?.toCharArray()
                }
            }
        return run(prompt, System.out, System.err)
    }

    private fun utf8ByteLength(password: CharArray): Int {
        val encoded = Charsets.UTF_8.encode(CharBuffer.wrap(password))
        try {
            return encoded.remaining()
        } finally {
            encoded.array().fill(0)
        }
    }

    private fun fail(
        err: PrintStream,
        message: String,
    ): Int {
        err.println(message)
        return 1
    }
}
