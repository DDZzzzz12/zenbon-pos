package org.example.project.infrastructure.di

import org.koin.core.qualifier.named

/**
 * Qualifier for the application-wide JSON serializer.
 *
 * Use this qualifier when injecting the Json instance for
 * encoding/decoding operations. The instance itself is registered by [serializationModule]
 * (host wiring); the qualifier lives apart so platform code can inject it without the module list.
 */
val AppJson = named("appJson")
