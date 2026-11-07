package org.example.project.features.appupdate.domain

/** Carries an [AppUpdateFailure] out of a platform call. */
class AppUpdateException(
    val failure: AppUpdateFailure,
) : RuntimeException(failure.name)
