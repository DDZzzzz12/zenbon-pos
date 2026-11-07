package org.example.project.admin.core.auth

import org.example.project.core.auth.TokenStore
import org.example.project.core.domain.enums.auth.Capability

/**
 * Editing shop configuration — the food-to-go VAT rule and its neighbours.
 *
 * **Its second leg was dead.** It used to be `isManagerOrHigher() || EXCEPTION_SYSTEM_CONFIG in
 * jwt.permissions`, and the grant half could never be true: Stage 2a retired per-employee grants and
 * the token's permission claim has been empty ever since. The KDoc explained that the role leg alone
 * "would hide the control from exactly the user the permission exists for" — a real concern, and the
 * grant mechanism it relied on had already stopped working.
 *
 * `CONFIG_WRITE` is that intent, working: it is a real capability on the server's configuration
 * PUTs, and a group can now confer it without making anyone a manager.
 */
val TokenStore.canConfigureSystem: Boolean
    get() = holds(Capability.CONFIG_WRITE)
