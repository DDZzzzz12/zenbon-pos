package org.example.project.core.utils

import org.example.project.features.appupdate.domain.AppUpdatePlatform

/** Android returns the real installer; every other platform a no-op that reports no installed build. */
expect fun createAppUpdatePlatform(): AppUpdatePlatform
