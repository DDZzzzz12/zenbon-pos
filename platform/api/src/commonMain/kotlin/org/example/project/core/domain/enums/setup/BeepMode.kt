package org.example.project.core.domain.enums.setup

import kotlinx.serialization.Serializable

@Serializable
enum class BeepMode {
    DISABLED,
    SHORT,
    LONG,
}
