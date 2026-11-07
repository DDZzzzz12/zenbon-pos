package org.example.project.core.domain.enums.tse

import kotlinx.serialization.Serializable

/** Who wrote an explanation: the system only where the cause is certain, otherwise an operator. */
@Serializable
enum class TseExplanationSource { SYSTEM, OPERATOR }
