package org.example.project.infrastructure.plugins

import io.ktor.server.application.*
import org.example.project.infrastructure.di.httpClientModule
import org.example.project.infrastructure.di.reposModule
import org.example.project.infrastructure.di.serializationModule
import org.example.project.infrastructure.di.servicesModule
import org.koin.core.context.GlobalContext
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("Application.configureKoin")

/**
 * Initializes dependency injection using Koin for Ktor server.
 *
 * This function installs the Koin Ktor plugin to allow routes and components
 * to inject dependencies. If a global Koin instance already exists (e.g., from
 * desktop app initialization), it will use that existing context. Otherwise,
 * it creates a new Koin instance with server modules.
 *
 * Note: This is separate from the global Koin instance used by the desktop app.
 * The Ktor plugin allows HTTP routes to access dependencies via dependency injection.
 */
fun Application.configureKoin() {
    try {
        logger.info("Configuring Koin for Ktor server...")

        // Check if global Koin is already started (e.g., by desktop app)
        val existingKoin = GlobalContext.getOrNull()

        if (existingKoin != null) {
            logger.info("Using existing global Koin context for Ktor routes")
            // Install Ktor plugin without starting a new Koin instance
            install(Koin) {
                // Point to the existing global Koin context
                // The plugin will use the existing instance
            }
        } else {
            logger.info("Starting new Koin instance for server-only mode")
            // Server is running standalone (not embedded in desktop app)
            install(Koin) {
                slf4jLogger()
                modules(serializationModule, httpClientModule, reposModule, servicesModule)
            }
        }

        logger.info("Koin configured successfully for Ktor")
    } catch (e: Exception) {
        logger.error("Failed to configure Koin for Ktor", e)
        throw e
    }
}
