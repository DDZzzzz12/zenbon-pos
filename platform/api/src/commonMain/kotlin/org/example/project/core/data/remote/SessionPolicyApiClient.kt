/**
 * API client interface for session-security policy operations.
 *
 * Wraps the admin-only session endpoints: daily re-login policy
 * (get/update) and the panic revoke-all action.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote

import org.example.project.auth.data.SessionPolicyDto

interface SessionPolicyApiClient {
    suspend fun getPolicy(): SessionPolicyDto.Policy

    suspend fun updatePolicy(req: SessionPolicyDto.UpdatePolicyRequest): SessionPolicyDto.Policy

    suspend fun revokeAll()
}
