package org.example.project.features.employee.domain.service

/**
 * Told after a staff change has committed that may alter who is on the roster or who is next in
 * line (employment status, role, rotation pool, deletion). Called holding no lock and outside any
 * transaction; implementations must not throw except for cancellation.
 */
fun interface StaffRosterListener {
    suspend fun onRosterChanged()
}
