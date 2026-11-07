/**
 * Koin dependency injection module for HTTP clients and TSE provider wiring.
 *
 * Provides configured HTTP client instances for different use cases:
 * - Internal service-to-service communication (TSE, microservices)
 * - External API calls
 *
 * TSE providers always drive the hardware in-process via the embedded server-tse module.
 * When the Swissbit SDK is absent an inert provider is used instead, so the server still
 * boots and signing degrades through the configured failure behavior.
 *
 * Providers are wrapped in delegating instances that support hot-swapping
 * when the TSE config changes at runtime (no server restart needed).
 *
 * Uses the shared HttpClientFactory for platform-optimized client creation.
 *
 * @since 1.0.0
 */
package org.example.project.infrastructure.di

import kotlinx.coroutines.runBlocking
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.network.createInternalServiceClient
import org.example.project.core.websocket.NotificationHub
import org.example.project.features.tse.data.provider.DelegatingTseAdminProvider
import org.example.project.features.tse.data.provider.DelegatingTseSignProvider
import org.example.project.features.tse.data.provider.TseSdkAvailability
import org.example.project.features.tse.data.provider.local.DevelopmentTseLocator
import org.example.project.features.tse.data.provider.local.LocalTseAdminProvider
import org.example.project.features.tse.data.provider.local.LocalTseLifecycleManager
import org.example.project.features.tse.data.provider.local.LocalTseSignProvider
import org.example.project.features.tse.data.provider.local.TseDriverClient
import org.example.project.features.tse.data.provider.unavailable.UnavailableTseAdminProvider
import org.example.project.features.tse.data.provider.unavailable.UnavailableTseSignProvider
import org.example.project.features.tse.domain.model.TseInitResult
import org.example.project.features.tse.domain.provider.TseAdminProvider
import org.example.project.features.tse.domain.provider.TseSignProvider
import org.koin.core.context.GlobalContext
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.slf4j.LoggerFactory

/**
 * Qualifier for the internal service HTTP client.
 *
 * Use this qualifier when injecting the HTTP client for
 * server-to-server communication (TSE, internal APIs).
 */
val InternalHttpClient = named("internalHttpClient")

private val logger = LoggerFactory.getLogger("HttpClientModule")

/** The key the TSE status on-connect listener is registered under; see [registerTseStatusListener]. */
private const val TSE_STATUS_LISTENER_KEY = "tse-status"

/**
 * Registers [provider]'s TSE status on-connect listener, replacing whichever one was registered before.
 *
 * Called at startup and after every provider hot-swap. `registerConnectionListener()` appends a new
 * listener to the notification hub each time it runs — the TSE module that builds it is protected and
 * stays as it is — so calling it directly left one more listener per TSE configuration change, and
 * every new stream then received one duplicate status frame per earlier change. The listener only
 * closes over the static status broadcaster, never over a provider instance, so one live registration
 * is correct across swaps.
 */
private fun registerTseStatusListener(provider: TseSignProvider) {
    NotificationHub.replaceOnConnectListeners(TSE_STATUS_LISTENER_KEY) { provider.registerConnectionListener() }
}

/**
 * HTTP client and TSE provider dependency injection module.
 *
 * Provides:
 * - Internal HTTP client for service-to-service communication
 * - TSE providers (sign + admin) for in-process hardware access
 * - LocalTseLifecycleManager for hardware lifecycle management
 * - Delegating wrappers for hot-swapping providers at runtime
 *
 * Usage:
 * ```kotlin
 * // Inject TSE sign provider
 * val tseSignProvider: TseSignProvider by inject()
 *
 * // Inject TSE admin provider
 * val tseAdminProvider: TseAdminProvider by inject()
 * ```
 */
val httpClientModule =
    module {
        /**
         * Internal HTTP client for service-to-service communication.
         *
         * Configured with:
         * - OkHttp engine with connection pooling
         * - JSON content negotiation
         * - Compression support (gzip, deflate)
         * - Server-optimized timeouts
         * - No authentication required
         *
         * Singleton instance shared across the application.
         */
        single(InternalHttpClient) {
            createInternalServiceClient()
        }

        /**
         * LocalTseLifecycleManager for in-process hardware access.
         *
         * Manages the TseStatus instance and TseMaintenanceTask for direct
         * hardware access.
         */
        // `locate` is injected so `:pos-server:runVirtual` can attach the in-memory TSE. The
        // synthetic mount point still goes through the same hardware validation, so a packaged
        // build — which carries the real SDK — simply fails to open it (E2E-2026-09-20 #1).
        //
        // `resolveClientId` is the one client lookup for startup, re-acquiring a TSE plugged in at
        // runtime and every self test (TseDriverClient: a registered active terminal, no fallback).
        single {
            val driverClient = TseDriverClient(get())
            LocalTseLifecycleManager(
                locate = { DevelopmentTseLocator.locate() },
                resolveClientId = { driverClient.resolve() },
            )
        }

        /**
         * TSE Sign Provider — delegating wrapper around [LocalTseSignProvider].
         *
         * Falls back to [UnavailableTseSignProvider] when the Swissbit SDK is absent.
         *
         * The delegating wrapper allows hot-swapping when the TSE config changes.
         */
        single<TseSignProvider> {
            val initial = createConcreteSignProvider()
            DelegatingTseSignProvider(initial).also { registerTseStatusListener(it) }
        }

        /**
         * TSE Admin Provider — delegating wrapper around [LocalTseAdminProvider].
         *
         * Falls back to [UnavailableTseAdminProvider] when the Swissbit SDK is absent.
         *
         * The delegating wrapper allows hot-swapping when the TSE config changes.
         */
        single<TseAdminProvider> {
            val initial = createConcreteAdminProvider()
            DelegatingTseAdminProvider(initial)
        }
    }

/**
 * Creates a concrete [TseSignProvider].
 *
 * Called during startup and when providers are hot-swapped.
 */
internal fun createConcreteSignProvider(): TseSignProvider {
    val koin = GlobalContext.get()

    // Constructing the provider pulls in LocalTseLifecycleManager, whose TseStatus field is
    // typed on a Swissbit class. Without the SDK that throws NoClassDefFoundError and kills
    // startup, so fall back to an inert provider whose calls fail the same way an offline
    // device does.
    if (!TseSdkAvailability.isPresent()) {
        logger.error(
            "The TSE SDK is missing — TSE signing is unavailable and follows the configured " +
                "failure behavior (QUEUE = Nachsignieren, FAIL = reject). " +
                "Install native/WormAPI.jar to restore it.",
        )
        return UnavailableTseSignProvider()
    }

    logger.info("Creating TseSignProvider (direct hardware access)")
    return LocalTseSignProvider(koin.get())
}

/**
 * Creates a concrete [TseAdminProvider].
 *
 * Called during startup and when providers are hot-swapped.
 */
internal fun createConcreteAdminProvider(): TseAdminProvider {
    val koin = GlobalContext.get()

    // Same guard as createConcreteSignProvider — see the note there.
    if (!TseSdkAvailability.isPresent()) {
        logger.error("The TSE SDK is missing — TSE administration is unavailable")
        return UnavailableTseAdminProvider()
    }

    logger.info("Creating TseAdminProvider (direct hardware access)")
    return LocalTseAdminProvider(
        lifecycleManager = koin.get(),
        terminalRepository = koin.get(),
        capabilityResolver = koin.get(),
    )
}

/**
 * Hot-swaps TSE providers after a TSE config change.
 *
 * Re-initializes the hardware lifecycle and creates new concrete providers,
 * then swaps the delegates in both [DelegatingTseSignProvider] and
 * [DelegatingTseAdminProvider]. The old providers are closed automatically.
 *
 * Without the Swissbit SDK there is no hardware to bring up and nothing to swap: the providers
 * already are the inert `Unavailable*` ones (the classpath cannot change while the JVM runs), and
 * resolving [LocalTseLifecycleManager] would throw `NoClassDefFoundError` — which is how a saved
 * `PUT /api/tse/config` used to answer 500 on a public build (T9-15). That case returns
 * [sdkMissingInitResult] instead, so the caller reports it like an unreachable device.
 *
 * @param sdkPresent whether the Swissbit SDK is on the classpath; a parameter so the missing-SDK
 *   path can be tested on a machine that has it.
 */
fun reloadTseProviders(
    configure: Boolean = true,
    sdkPresent: Boolean = TseSdkAvailability.isPresent(),
): TseInitResult {
    if (!sdkPresent) {
        logger.error("TSE config changed, but the TSE SDK is missing — no TSE hardware to initialize")
        return sdkMissingInitResult()
    }

    val koin = GlobalContext.get()
    val signWrapper = koin.get<TseSignProvider>() as DelegatingTseSignProvider
    val adminWrapper = koin.get<TseAdminProvider>() as DelegatingTseAdminProvider

    logger.info("Hot-swapping TSE providers...")

    // Initialize hardware before creating the providers. A LinkageError here is a SDK that the
    // probe class did not catch (e.g. a partial or mismatched WormAPI.jar) — same answer.
    val initResult: TseInitResult =
        try {
            val manager = koin.get<LocalTseLifecycleManager>()
            runBlocking { manager.initialize(configure) }
        } catch (e: LinkageError) {
            logger.error("TSE SDK could not be loaded while re-initializing the TSE: ${e.message}")
            return sdkMissingInitResult()
        }

    val newSign = createConcreteSignProvider()
    val newAdmin = createConcreteAdminProvider()

    adminWrapper.switchDelegate(newAdmin)
    signWrapper.switchDelegate(newSign)

    // Replace — never add to — the listener the startup registration or the previous swap installed.
    registerTseStatusListener(newSign)

    logger.info("TSE providers hot-swapped successfully (init=$initResult)")
    return initResult
}

/** The message a config change on a server without the Swissbit SDK surfaces to the operator. */
internal const val TSE_SDK_MISSING_REASON = "TSE SDK not installed — place the Swissbit WormAPI.jar at native/WormAPI.jar and restart the server"

/**
 * What a TSE (re)initialization amounts to without the SDK: DISABLED is still simply disabled;
 * any other mode cannot reach a device, which the failure behaviour already covers (QUEUE =
 * Nachsignieren). Mirrors the 424 the TSE admin endpoints answer in the same state.
 */
internal fun sdkMissingInitResult(): TseInitResult {
    val mode = runCatching { runBlocking { ShopConfigurationManager.getTseMode() } }.getOrNull()
    return if (mode == TseMode.DISABLED) TseInitResult.Disabled else TseInitResult.Unreachable(TSE_SDK_MISSING_REASON)
}
