package org.example.project.core.presentation.error

import androidx.compose.runtime.compositionLocalOf

/**
 * Name of the screen currently on top of the navigation stack.
 *
 * Read by [org.example.project.ui.components.PosErrorWatcher] so every reported error records
 * where it happened. Supplied once per app, around the NavHost, rather than passed at each
 * watcher call site: "which screen am I on" is ambient, and threading it through ~60 call sites
 * would make it exactly the sort of thing a new screen forgets — which is the failure this whole
 * change set has been removing.
 *
 * Null where no navigation host has provided one (tests, previews, a composable hosted outside
 * the graph), which the log records as an empty screen rather than a wrong one.
 */
val LocalScreenName = compositionLocalOf<String?> { null }
