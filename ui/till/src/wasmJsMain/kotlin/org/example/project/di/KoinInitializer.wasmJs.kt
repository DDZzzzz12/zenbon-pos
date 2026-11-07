package org.example.project.di

import org.koin.core.module.Module

/**
 * Browser-specific DI modules.
 *
 * Common modules (repositories, services, ViewModels) are already loaded via `getAllModules()`.
 */
actual fun getPlatformModules(): List<Module> = listOf(wasmModule)
