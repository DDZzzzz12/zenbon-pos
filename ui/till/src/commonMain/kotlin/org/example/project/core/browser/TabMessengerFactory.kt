package org.example.project.core.browser

/** The tab-to-tab channel of this platform. Only the browser has one; elsewhere it is silent. */
expect fun createTabMessenger(): TabMessenger
