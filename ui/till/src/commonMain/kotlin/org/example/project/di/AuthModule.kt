package org.example.project.di

import org.example.project.core.auth.PermissionManager
import org.example.project.core.auth.SessionRoleLoader
import org.example.project.core.data.remote.ApprovalRequestApiClient
import org.example.project.core.data.remote.AuthApiClient
import org.example.project.core.data.remote.impl.KtorApprovalRequestApiClient
import org.example.project.core.data.remote.impl.KtorAuthApiClient
import org.example.project.core.domain.rules.TableAccessRuleEngine
import org.example.project.features.auth.data.remote.KtorTemporarySessionApiClient
import org.example.project.features.auth.data.remote.TemporarySessionApiClient
import org.example.project.features.auth.domain.usecase.LoginUseCase
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Authentication dependency injection module.
 *
 * Provides authentication-related dependencies including
 * HTTP clients, repositories, and services.
 *
 * Dependencies structure:
 * 1. Auth API client uses unauthenticated HTTP client (for login/logout)
 * 2. Auth repository uses auth API client and local database manager
 *
 * Token persistence ([org.example.project.core.auth.TokenStore]) and app-level
 * session state ([org.example.project.ui.auth.SessionState]) are registered in
 * [commonDatabaseModule] alongside the [KeyValueStore] they sit on.
 */
val authModule =
    module {

        /**
         * Permission Manager.
         *
         * Manages role-based access control and permission checking.
         * Automatically extracts permissions from JWT tokens and provides
         * reactive permission state for UI components.
         */
        singleOf(::PermissionManager)

        /**
         * Session Role Loader.
         *
         * Publishes the stored token's role to the Permission Manager while the session is still authenticated.
         * Explicit rather than `singleOf`, for the defaulted IO dispatcher — see [LoginUseCase] below.
         */
        single { SessionRoleLoader(permissionManager = get(), sessionState = get()) }

        singleOf(::TableAccessRuleEngine)

        /**
         * Authentication API Client.
         *
         * HTTP client for authentication endpoints using Ktor.
         */
        singleOf(::KtorAuthApiClient) bind AuthApiClient::class

        /**
         * Temporary Session API Client.
         *
         * HTTP client for temporary manager session endpoints.
         * Handles creation, extension, and termination of time-limited
         * privilege escalation sessions for BASIC_EMPLOYEE users.
         */
        singleOf(::KtorTemporarySessionApiClient) bind TemporarySessionApiClient::class

        /**
         * Approval Request API Client.
         *
         * HTTP client for manager approval request endpoints.
         * Handles listing, approving, and denying pending approval requests
         * from cashiers in the real-time authorization workflow.
         */
        singleOf(::KtorApprovalRequestApiClient) bind ApprovalRequestApiClient::class

        /**
         * Login Use Case.
         *
         * Handles login business logic including validation and authentication.
         *
         * Explicit rather than `singleOf`: that would resolve every constructor parameter, including the
         * defaulted IO dispatcher, which is not a Koin binding — and `KoinModuleGraphTest` cannot catch it,
         * because Koin's verify treats a defaulted parameter as satisfied.
         */
        single { LoginUseCase(get(), get(), get(), get(), get(), get()) }
    }
