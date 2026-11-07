package org.example.project.core.utils

actual fun sha256(bytes: ByteArray): ByteArray = throw UnsupportedOperationException("SHA-256 is not available in the browser build")
