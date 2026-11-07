package org.example.project.features.update.data.service

import kotlin.system.exitProcess

/**
 * Ends the JVM from a fresh thread, a moment after the caller returns.
 *
 * `System.exit` runs the shutdown hooks and waits for them. The server's hook stops Netty, whose graceful
 * shutdown waits for every event-loop thread to finish its task. [UpdateApplier.apply] runs inside the HTTP
 * handler of `POST /api/update/apply`, i.e. ON an event-loop thread, so calling `exit` there makes the
 * thread wait for a hook that waits for that very thread: the process then hangs for good (no exit, so
 * systemd never restarts it), with the new jar already swapped in and the database already stopped.
 * Exiting from a thread of our own breaks the cycle, and the short [delayMs] lets the HTTP response leave first.
 */
fun exitFromNewThread(
    code: Int,
    delayMs: Long = DEFAULT_DELAY_MS,
    exit: (Int) -> Unit = { exitProcess(it) },
): Thread =
    Thread({
        Thread.sleep(delayMs)
        exit(code)
    }, "update-exit").apply { start() }

private const val DEFAULT_DELAY_MS = 500L
