package org.example.project.core.browser

actual object PageReloader {
    actual fun reload() = Unit

    actual fun readLastAutoReload(): Long? = null

    actual fun writeLastAutoReload(epochMillis: Long) = Unit
}
