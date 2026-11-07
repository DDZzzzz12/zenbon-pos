package org.example.project.features.order.data.service

import org.example.project.features.employee.domain.service.StaffRosterListener

/** Publishes the rotation's "who is next" after a staff change committed. */
class RotationStaffRosterListener : StaffRosterListener {
    override suspend fun onRosterChanged() = RotationNextNotifier.publishCurrent()
}
