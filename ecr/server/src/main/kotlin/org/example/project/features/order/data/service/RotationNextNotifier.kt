package org.example.project.features.order.data.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.example.project.core.domain.dto.rotation.RotationDto
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.websocket.NotificationService
import org.example.project.features.order.domain.service.RotationAssignmentService
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Publishes "who is next in the round-robin" to every connected client and serves the matching snapshot.
 *
 * One [Mutex] guards both [publishCurrent] and [snapshot], and the version counter only moves under it, so
 * broadcast order equals version order and a snapshot's version is always consistent with its `next`.
 *
 * **Call [publishCurrent] only AFTER the DB transaction that changed the rotation has committed**, and never
 * while holding the r_table / rotation / config locks: it reads the DB itself (via
 * [RotationAssignmentService.peekNext]) and would otherwise either see uncommitted state or invert the
 * lock order (r_table -> rotationMutex -> cursor row).
 */
@OptIn(ExperimentalTime::class)
object RotationNextNotifier {
    private val logger = LoggerFactory.getLogger(RotationNextNotifier::class.java)
    private val mutex = Mutex()

    /**
     * Seeded from the wall clock (epoch ms) when this process first touches the object, normally via
     * [install] while the DI graph is built, so a restarted server continues ABOVE every version its clients
     * saw before the restart instead of starting again at 0, which a client would drop as stale until its
     * reconcile snapshot arrived. Holds as long as the previous process published fewer events than the
     * milliseconds it ran, and as long as the clock is not set back across the restart.
     */
    private var version = System.currentTimeMillis()
    private var service: RotationAssignmentService? = null

    /** Wires the service used to compute the next employee. Called once when the DI graph is built. */
    fun install(service: RotationAssignmentService) {
        this.service = service
    }

    /** Current next employee with the current version, without bumping it. */
    suspend fun snapshot(): RotationDto.NextSnapshotDto =
        mutex.withLock {
            RotationDto.NextSnapshotDto(version = version, next = requireService().peekNext())
        }

    /**
     * Bumps the version, reads the current next employee and broadcasts it to all clients.
     * Never throws (except cancellation): a broadcast problem must not fail the caller.
     */
    suspend fun publishCurrent() {
        try {
            mutex.withLock {
                version++
                val update =
                    NotificationDto.RotationNextUpdateDto(
                        timestamp = Clock.System.now().toString(),
                        version = version,
                        next = requireService().peekNext(),
                    )
                NotificationService.notifyRotationNext(update)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to publish rotation next update", e)
        }
    }

    private fun requireService(): RotationAssignmentService = checkNotNull(service) { "RotationNextNotifier.install() has not been called" }
}
