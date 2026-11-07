package org.example.project.core.data.cache

import org.example.project.core.domain.enums.websocket.ChangeEntityType

/** A signal that the local cache for [entityType] changed, optionally scoped to [locationTypeId]. */
data class CacheChange(
    val entityType: ChangeEntityType,
    val locationTypeId: Long? = null,
)
