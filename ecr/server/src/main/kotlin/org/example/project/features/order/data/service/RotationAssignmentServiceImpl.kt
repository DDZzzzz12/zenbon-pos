package org.example.project.features.order.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.dto.employee.EmployeeDto
import org.example.project.core.domain.dto.rotation.RotationDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.employee.domain.repository.EmployeeInfoRepository
import org.example.project.features.employee.domain.repository.EmployeeRepository
import org.example.project.features.employee.domain.repository.PermissionGroupRepository
import org.example.project.features.employee.mapper.toEmployeeResponse
import org.example.project.features.order.domain.service.RotationAssignmentService
import org.example.project.features.order.domain.service.nextInRotation
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.slf4j.LoggerFactory

/**
 * Picks the next employee from the active rotation pool using round-robin order.
 *
 * The cursor is read from its database row under `FOR UPDATE` and written back in the caller's
 * transaction ([IShopConfigurationManager.getOrderRotationCursor]), so a pick never builds on a value
 * another transaction cannot see — reading it from the shared configuration cache let two same-second
 * station opens go to one waiter (emu20 NOTE-r). The process-wide [rotationMutex] additionally
 * serializes the read→compute→write in this process, including opens of different tables at the same
 * instant. After the pool wraps, picks restart from the beginning.
 */
class RotationAssignmentServiceImpl(
    private val employeeRepository: EmployeeRepository,
    private val configManager: IShopConfigurationManager,
    private val permissionGroups: PermissionGroupRepository,
    private val employeeInfoRepository: EmployeeInfoRepository,
) : RotationAssignmentService {
    private val logger = LoggerFactory.getLogger(RotationAssignmentServiceImpl::class.java)

    override suspend fun pickNextEmployee(): EmployeeDto.EmployeeResponseDto =
        rotationMutex.withLock {
            dbQuery {
                val pool = employeeRepository.getActiveRotationPool()
                if (pool.isEmpty()) {
                    throw PosException(
                        PosErrorCode.ROTATION_POOL_EMPTY,
                        HttpStatusCode.Conflict,
                        "Rotation pool is empty — no active non-terminal employee is in the rotation pool",
                    )
                }

                val cursor = configManager.getOrderRotationCursor()
                val pickedId = checkNotNull(nextInRotation(pool.map { it.id.value }, cursor)) { "pool is non-empty" }
                val picked = pool.first { it.id.value == pickedId }

                configManager.setOrderRotationCursor(picked.id.value)
                logger.info("pickNextEmployee: round-robin picked employee={}", picked.employeeNumber)

                picked.toEmployeeResponse(permissionGroups.groupCodesOf(listOf(picked.id.value))[picked.id.value])
            }
        }

    /**
     * Read-only twin of [pickNextEmployee]: same pool, same [nextInRotation] rule, but the cursor is read
     * without a row lock (and never created) and nothing is written, so it takes no [rotationMutex] — the
     * lock-order rules apply only to the writing pick. The answer may be stale by the time it is shown.
     */
    override suspend fun peekNext(): RotationDto.EmployeeRefDto? {
        if (!configManager.isRoundRobinAssignment()) return null
        return dbQuery {
            val pool = employeeRepository.getActiveRotationPool()
            val cursor = configManager.peekOrderRotationCursor()
            val nextId = nextInRotation(pool.map { it.id.value }, cursor) ?: return@dbQuery null
            val next = pool.first { it.id.value == nextId }
            RotationDto.EmployeeRefDto(
                employeeId = nextId.toString(),
                employeeNumber = next.employeeNumber,
                displayName = employeeInfoRepository.getEmployeeInfoByEmployeeId(nextId)?.firstName,
            )
        }
    }

    companion object {
        /**
         * Process-wide lock serializing all rotation picks (single-process POS server).
         *
         * Lock ordering: in the table-open path this is acquired AFTER the r_table FOR UPDATE row
         * lock and BEFORE the shop_configuration cursor row lock. Never invert these.
         */
        private val rotationMutex = Mutex()
    }
}
