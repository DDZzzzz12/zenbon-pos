package org.example.project.features.order.domain.service

import org.example.project.core.domain.dto.employee.EmployeeDto
import org.example.project.core.domain.dto.rotation.RotationDto

/**
 * Picks the next employee in round-robin order for the round-robin order rule.
 * Persists the cursor across calls so two consecutive picks return different
 * employees (modulo pool wrap).
 */
interface RotationAssignmentService {
    /**
     * Pick the next employee in round-robin order from the active rotation pool
     * and advance the persistent cursor within the same DB transaction.
     *
     * @return [EmployeeDto.EmployeeResponseDto] of the picked employee
     * @throws org.example.project.infrastructure.errors.PosException with
     *         [org.example.project.core.error.PosErrorCode.ROTATION_POOL_EMPTY] when no
     *         active employee is in the rotation pool.
     */
    suspend fun pickNextEmployee(): EmployeeDto.EmployeeResponseDto

    /**
     * The employee the next [pickNextEmployee] would return, without advancing the cursor, taking any
     * lock or writing anything.
     *
     * @return null when round-robin assignment is off or the rotation pool is empty
     */
    suspend fun peekNext(): RotationDto.EmployeeRefDto?
}
