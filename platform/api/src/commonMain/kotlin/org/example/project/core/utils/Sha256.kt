package org.example.project.core.utils

/** SHA-256 of [bytes]. Not available in the browser build, where TLS trust is the browser's job. */
expect fun sha256(bytes: ByteArray): ByteArray
