/**
 * Runtime detection of the proprietary Swissbit TSE SDK.
 *
 * The SDK (WormAPI.jar) is deliberately not bundled into pos-server.jar — :pos-tse
 * declares it `compileOnly` and the JAR resolves it at runtime through a
 * `Class-Path: native/WormAPI.jar` manifest entry. Internal releases ship it under
 * `native/`; public open-source releases do not, and the operator supplies their own.
 *
 * Without this check, selecting LOCAL connection mode constructs
 * [org.example.project.features.tse.data.provider.local.LocalTseLifecycleManager],
 * whose TseStatus field is typed on a Swissbit class — which throws
 * NoClassDefFoundError during Koin startup and kills the whole process before the
 * server can report anything useful.
 *
 * @since 1.0.0
 */
package org.example.project.features.tse.data.provider

import org.slf4j.LoggerFactory

/**
 * Reports whether the Swissbit TSE SDK is present on the runtime classpath.
 *
 * Result is cached: the classpath cannot change while the JVM runs, and this is
 * consulted on every provider construction and every TSE config change.
 */
object TseSdkAvailability {
    private val logger = LoggerFactory.getLogger(TseSdkAvailability::class.java)

    /**
     * A Swissbit class that [org.example.project.tse.models.TseStatus] references
     * directly, so its absence is exactly what triggers the startup crash we guard.
     */
    private const val PROBE_CLASS = "com.secureflashcard.wormapi.WormInitializationState"

    private val present: Boolean by lazy {
        try {
            Class.forName(PROBE_CLASS, false, TseSdkAvailability::class.java.classLoader)
            logger.info("TSE SDK detected on the classpath — LOCAL connection mode is available")
            true
        } catch (e: ClassNotFoundException) {
            logger.warn(
                "TSE SDK not found on the classpath ({}). LOCAL connection mode is unavailable; " +
                    "place the Swissbit WormAPI.jar at native/WormAPI.jar next to pos-server.jar to enable it. " +
                    "The server continues to run and TSE signing follows the configured failure behavior.",
                e.message,
            )
            false
        }
    }

    /**
     * @return true when the Swissbit SDK can be loaded, false when it is absent.
     */
    fun isPresent(): Boolean = present
}
