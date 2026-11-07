package org.example.project.admin.features.bootstrap.presentation

/**
 * Result of the `GET /api/setup/status` probe (Terminal Phase RB3 Part A). Drives the
 * pre-auth [BootstrapGate] in `AdminApp`: PROBING shows a spinner, NEEDS_BOOTSTRAP shows the
 * owner/shop wizard, INITIALIZED falls through to the normal login screen.
 */
enum class BootstrapStatus { PROBING, NEEDS_BOOTSTRAP, INITIALIZED }
