package org.example.project.core.utils

import android.app.Application
import org.example.project.core.appupdate.AndroidAppUpdatePlatform
import org.example.project.features.appupdate.domain.AppUpdatePlatform
import org.koin.java.KoinJavaComponent.get

actual fun createAppUpdatePlatform(): AppUpdatePlatform = AndroidAppUpdatePlatform(get(Application::class.java))
