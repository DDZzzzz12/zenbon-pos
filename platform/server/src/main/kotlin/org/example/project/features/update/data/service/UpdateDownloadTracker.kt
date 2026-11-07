package org.example.project.features.update.data.service

import org.example.project.core.domain.dto.update.UpdateDto
import java.util.concurrent.atomic.AtomicReference

/**
 * What the staging run in flight has downloaded so far, for the back office to poll.
 *
 * In memory and per process, on purpose: progress is a fact about *this* run and means nothing
 * after a restart, so persisting it would only leave a stale "downloading" behind for a process
 * that is no longer doing so. [clear] therefore runs in a `finally` around the whole run.
 *
 * Reads and writes race freely (the download thread writes, a status request reads), so the state
 * is one immutable snapshot swapped atomically -- a reader can never see a phase from one run with
 * the byte count of another.
 */
class UpdateDownloadTracker {
    private val current = AtomicReference<UpdateDto.DownloadProgressDto?>(null)

    /** A [phase] has begun; [bytesTotal] is null when its size is not known up front. */
    fun start(
        phase: UpdateDto.DownloadPhase,
        bytesTotal: Long? = null,
    ) {
        current.set(UpdateDto.DownloadProgressDto(phase, bytesDone = 0, bytesTotal = bytesTotal?.takeIf { it > 0 }))
    }

    /** Records [bytesDone] for the running phase. A report with no phase running is ignored. */
    fun report(
        bytesDone: Long,
        bytesTotal: Long? = null,
    ) {
        current.updateAndGet { running ->
            running?.copy(
                bytesDone = bytesDone,
                bytesTotal = bytesTotal?.takeIf { it > 0 } ?: running.bytesTotal,
            )
        }
    }

    /** Adds [bytes] to the running phase, for a download that arrives in pieces (the delta ranges). */
    fun advance(bytes: Long) {
        current.updateAndGet { running -> running?.copy(bytesDone = running.bytesDone + bytes) }
    }

    fun clear() {
        current.set(null)
    }

    fun snapshot(): UpdateDto.DownloadProgressDto? = current.get()
}
