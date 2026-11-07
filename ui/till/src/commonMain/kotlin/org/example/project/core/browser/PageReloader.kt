package org.example.project.core.browser

/** Reloads the page that hosts the app. Only the browser client has one; elsewhere it is a no-op. */
expect object PageReloader {
    /** Drops the leave-confirmation first, so the browser's own "Leave site?" dialog cannot stall the reload. */
    fun reload()

    /** The time of the last automatic reload of this tab session, or null. Never throws. */
    fun readLastAutoReload(): Long?

    /** Remembers [epochMillis] as the last automatic reload. Never throws. */
    fun writeLastAutoReload(epochMillis: Long)
}

/** [ReloadStampStore] backed by the platform's [PageReloader]. */
object PageReloaderStampStore : ReloadStampStore {
    override fun read(): Long? = PageReloader.readLastAutoReload()

    override fun write(epochMillis: Long) = PageReloader.writeLastAutoReload(epochMillis)
}
