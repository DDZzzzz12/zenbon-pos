package org.example.project.admin.features.staff.presentation

/**
 * Whether a staff record is shown read-only: any record whose role is ADMIN.
 *
 * The directory lists admin rows to an ADMIN (FO-52) so the hierarchy is visible, but that must not
 * hand anyone a way to act on one from this screen (decision D5). The server refuses a non-admin
 * caller on an admin target; this is the client half for everyone, so edit, role, group,
 * credential, deactivation and schedule controls are hidden on such a record.
 */
fun isReadOnlyStaffTarget(role: String?): Boolean = role == "ADMIN"
