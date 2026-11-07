package org.example.project.features.tse.data.provider.local

import org.example.project.core.domain.enums.setup.TseMode

/**
 * What one TSE setup hands the device: the operator's one-way credentials, the first client and
 * the mode the device is commissioned in.
 *
 * Deliberately not a data class — a generated `toString` would print the Admin PIN, PUK and seed
 * into any log line or assertion message that formats the request.
 */
class TseCommissioningRequest(
    val adminPin: String,
    val adminPuk: String,
    val timeAdminPin: String,
    val credentialSeed: String,
    val clientId: String,
    val mode: TseMode,
)
