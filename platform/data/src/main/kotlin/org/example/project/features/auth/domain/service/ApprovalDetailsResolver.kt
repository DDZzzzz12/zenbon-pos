package org.example.project.features.auth.domain.service

/**
 * Turns raw ids in an approval request's details into human-readable values for the approving
 * manager. Resolvers are chained: each receives the previous one's output. A resolver handles its
 * own failures and returns what it has.
 */
fun interface ApprovalDetailsResolver {
    suspend fun resolve(details: Map<String, String>): Map<String, String>
}
