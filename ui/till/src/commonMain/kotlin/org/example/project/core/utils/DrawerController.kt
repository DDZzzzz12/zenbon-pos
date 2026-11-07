/**
 * Drawer controller for managing navigation drawer state across screens.
 *
 * Provides a way for child screens to toggle the navigation drawer
 * without direct access to the drawer state.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.utils

import androidx.compose.runtime.compositionLocalOf

/**
 * Controller interface for drawer operations.
 */
interface DrawerController {
    /**
     * Toggle the drawer open/closed.
     */
    fun toggleDrawer()

    /**
     * Check if drawer is available (only in COMPACT mode).
     */
    var isDrawerAvailable: Boolean
}

/**
 * CompositionLocal for accessing drawer controller in child screens.
 */
val LocalDrawerController = compositionLocalOf<DrawerController?> { null }

/**
 * No-op drawer controller for screens without drawer (tablet/desktop).
 */
object NoOpDrawerController : DrawerController {
    override fun toggleDrawer() {
        // No drawer available
    }

    override var isDrawerAvailable: Boolean = false
}
