package org.example.project.testutils.fakes

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [LogWriter] that records every entry written through its own [logger]. Hand that logger to the code under test: Kermit's
 * global writers are never touched, so nothing needs restoring after a test.
 */
class CapturingLogWriter : LogWriter() {
    private val entries = CopyOnWriteArrayList<Triple<Severity, String, Throwable?>>()

    val logger: Logger by lazy { Logger(StaticConfig(Severity.Verbose, listOf(this)), "test") }

    override fun log(
        severity: Severity,
        message: String,
        tag: String,
        throwable: Throwable?,
    ) {
        entries += Triple(severity, message, throwable)
    }

    /** The messages logged at [severity], in order. */
    fun messages(severity: Severity): List<String> = entries.filter { it.first == severity }.map { it.second }

    /** How many entries, at any severity, carried a throwable. */
    fun entriesWithThrowable(): Int = entries.count { it.third != null }
}
