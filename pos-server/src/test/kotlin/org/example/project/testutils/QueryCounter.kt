package org.example.project.testutils

import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.GlobalStatementInterceptor
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures the SQL Exposed executes while [record] runs.
 *
 * Why a GLOBAL interceptor and not `Transaction.registerInterceptor`: the read paths under test
 * run inside `dbQuery { }`, which is `withContext(Dispatchers.IO) { suspendTransaction { } }`.
 * That transaction is created on an IO thread the test never holds a reference to, so there is
 * nothing to register a per-transaction interceptor on. Exposed 1.3.1 keeps its global
 * interceptors in a public mutable list that is read live on every execution
 * (`JdbcTransaction.kt:323` declares it, `BlockingExecutable.kt:126` reads it), and every
 * statement — SELECT included — reaches it via `JdbcTransaction.exec` -> `executeIn`.
 *
 * COUNTING SEMANTICS — read this before asserting on a write path. Exposed invokes
 * [beforeExecution] once per ARGUMENT SET, not once per executed statement
 * (`BlockingExecutable.kt:123-135`), so the captured list counts argument sets rather than JDBC
 * round trips. For reads the two coincide: `AbstractQuery.arguments()` yields at most one argument
 * set, so one `SELECT` is one entry — which is why exact-count assertions over read paths are
 * sound. For batched writes they do NOT: three entities flushed as a single `BatchInsertStatement`
 * (one `executeBatch` round trip) appear as three identical `INSERT` entries. Count the SQL text
 * you care about; never read the list size as a round-trip count.
 *
 * The absence of a `META-INF/services/org.jetbrains.exposed.v1.core.statements.GlobalStatementInterceptor`
 * resource is DELIBERATE, not an oversight. Exposed ServiceLoader-loads that file into
 * `globalInterceptors` when the class initialises; providing it would arm this interceptor
 * process-wide for every test in the JVM. Registration must stay scoped to [record].
 *
 * Because the capture is process-wide, [record] is not re-entrant and always unregisters itself
 * again: pos-server tests share one forked JVM per 5 classes.
 */
object QueryCounter : GlobalStatementInterceptor {
    private val recording = AtomicBoolean(false)
    private val captured = CopyOnWriteArrayList<String>()

    override fun beforeExecution(
        transaction: Transaction,
        context: StatementContext,
    ) {
        if (recording.get()) {
            captured.add(context.sql(transaction))
        }
    }

    /**
     * Runs [block] and returns its result together with the prepared SQL Exposed executed while it
     * ran, in execution order, one entry per argument set — see the class KDoc: a batched write
     * yields one entry per row, not one per round trip. Parameters render as `?`, so a batched
     * lookup is recognisable by `IN (?, ?, ?)` and an N+1 by repeated `= ?`.
     */
    fun <T> record(block: () -> T): Pair<T, List<String>> {
        // Guard BEFORE touching the buffer: clearing first would let a nested or concurrent call
        // wipe the outer recording's capture on its way to throwing, and an outer exact-count
        // assertion could then pass on truncated data.
        check(recording.compareAndSet(false, true)) { "QueryCounter.record is not re-entrant" }
        captured.clear()
        if (!JdbcTransaction.globalInterceptors.contains(this)) {
            JdbcTransaction.globalInterceptors.add(this)
        }
        try {
            val value = block()
            return value to captured.toList()
        } finally {
            recording.set(false)
            JdbcTransaction.globalInterceptors.remove(this)
            // Deliberately NOT clearing here: the entry-clear already guarantees freshness, and
            // clearing on the throwing path would discard exactly the SQL worth inspecting when a
            // downstream perf test blows up.
        }
    }

    /**
     * Convenience for assertions that only care about the size of [record]'s capture. That size is
     * a count of argument sets, not of JDBC round trips — see the class KDoc before using it on a
     * path that writes.
     */
    fun count(block: () -> Unit): Int = record(block).second.size
}
