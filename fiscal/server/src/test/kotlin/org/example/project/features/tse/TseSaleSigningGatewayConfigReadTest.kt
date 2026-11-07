package org.example.project.features.tse

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.tse.TseEntityType
import org.example.project.core.domain.enums.tse.TseFailureBehavior
import org.example.project.core.domain.enums.tse.TseLateSigningReason
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.features.audit.domain.service.TransactionAuditService
import org.example.project.features.bill.domain.service.SaleSigningAttempt
import org.example.project.features.bill.domain.service.SaleSigningKind
import org.example.project.features.bill.domain.service.SaleSigningRequest
import org.example.project.features.tse.data.service.TseSaleSigningGateway
import org.example.project.features.tse.data.service.TseVorgangSigner
import org.example.project.features.tse.domain.TseSignOutcome
import org.example.project.features.tse.domain.TseSigningOutcomeResolver
import org.example.project.features.tse.domain.factory.TseServiceFactory
import org.example.project.features.tse.domain.provider.TseSignProvider
import org.example.project.features.tse.domain.repository.TseLateSigningRepository
import org.example.project.features.tse.domain.repository.TseTransactionRepository
import org.example.project.features.tse.domain.service.TseSigningQueueService
import org.example.project.features.tse.domain.toLateSigningCause
import org.example.project.infrastructure.errors.PosException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.Collections
import kotlin.test.assertEquals

/**
 * Pins the configuration contract of [TseSaleSigningGateway] (B2-4 final review M4):
 *
 * - `attempt(request, mode, failureBehavior)` reads **no** configuration from its injected
 *   [IShopConfigurationManager]; the decision follows the arguments alone. `settleAtTill` relies on this,
 *   because it reads the TSE mode and the failure behaviour before its Phase-1 commit and passes them in.
 * - `attempt(request)` reads the mode, then the failure behaviour (in that order), and nothing else.
 *
 * Only the gateway's injected manager is observed. [TseServiceFactory] chooses its device service from
 * the global configuration on its own; that lookup belongs to the factory, not to this contract. The
 * device is unreachable, so the sign ends either as an unreachable failure (real service) or without a
 * signature (disabled fallback); the expected decision is derived from whichever the device saw.
 */
@DisplayName("TseSaleSigningGateway reads TSE configuration only in the one-argument overload")
class TseSaleSigningGatewayConfigReadTest {
    private val configReads: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val deviceCalls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val request =
        SaleSigningRequest(
            kind = SaleSigningKind.RECEIPT,
            processData = "Beleg^0.00_0.00_0.00_0.00_0.00^",
            referenceId = "42",
            clientId = "CLIENT-1",
            entityType = TseEntityType.BILL,
        )

    @Test
    fun `the three-argument overload reads no configuration in any mode or failure behaviour`() =
        runTest {
            for (mode in TseMode.entries) {
                for (failureBehavior in TseFailureBehavior.entries) {
                    configReads.clear()
                    deviceCalls.clear()

                    val attempt = gateway().attempt(request, mode, failureBehavior)

                    assertEquals(emptyList(), configReads.toList(), "no config read for $mode/$failureBehavior")
                    assertEquals(expectedAttempt(mode, failureBehavior), attempt.decision(), "decision for $mode/$failureBehavior")
                }
            }
        }

    @Test
    fun `the one-argument overload reads the mode, then the failure behaviour, and decides by them`() =
        runTest {
            val attempt = gateway(configuredMode = TseMode.NORMAL, configuredFailure = TseFailureBehavior.FAIL).attempt(request)

            assertEquals(listOf("getTseMode", "getTseConfig"), configReads.toList())
            assertEquals(expectedAttempt(TseMode.NORMAL, TseFailureBehavior.FAIL), attempt.decision())
        }

    /**
     * What the gateway must return for [mode]/[failureBehavior]: the resolver's decision for the outcome the
     * unreachable device produced. A rethrown error is compared by code and message, not by identity.
     */
    private fun expectedAttempt(
        mode: TseMode,
        failureBehavior: TseFailureBehavior,
    ): Decision {
        val outcome =
            if (deviceCalls.isEmpty()) {
                TseSignOutcome.NoSignature
            } else {
                TseSignOutcome.Failed(PosException(PosErrorCode.TSE_UNREACHABLE, HttpStatusCode.ServiceUnavailable, UNREACHABLE_MESSAGE))
            }
        val decision = TseSigningOutcomeResolver.resolve(mode, failureBehavior, outcome)
        val cause = if (decision.enqueue) outcome.toLateSigningCause() else null
        return Decision(
            signed = false,
            status = decision.status,
            enqueue = decision.enqueue,
            rethrow = decision.rethrow?.let { it.code to it.message },
            lateReason = cause?.first,
            lateReasonText = cause?.second,
        )
    }

    private fun SaleSigningAttempt.decision(): Decision =
        Decision(
            signed = transaction != null,
            status = status,
            enqueue = enqueue,
            rethrow = rethrow?.let { it.code to it.message },
            lateReason = lateReason,
            lateReasonText = lateReasonText,
        )

    private data class Decision(
        val signed: Boolean,
        val status: TseSigningStatus,
        val enqueue: Boolean,
        val rethrow: Pair<PosErrorCode, String?>?,
        val lateReason: TseLateSigningReason?,
        val lateReasonText: String?,
    )

    private fun gateway(
        configuredMode: TseMode = TseMode.NORMAL,
        configuredFailure: TseFailureBehavior = TseFailureBehavior.QUEUE,
    ): TseSaleSigningGateway =
        TseSaleSigningGateway(
            tseServiceFactory =
                TseServiceFactory(
                    tseTransactionRepository = untouched(),
                    tseSignClient = unreachableDevice(),
                    signer = TseVorgangSigner(unreachableDevice(), untouched<TseTransactionRepository>(), untouched<TransactionAuditService>()),
                ),
            tseSigningQueueService = untouched<TseSigningQueueService>(),
            configManager = recordingConfig(configuredMode, configuredFailure),
            lateSigningRepository = untouched<TseLateSigningRepository>(),
        )

    private fun recordingConfig(
        mode: TseMode,
        failureBehavior: TseFailureBehavior,
    ): IShopConfigurationManager =
        Proxy.newProxyInstance(
            IShopConfigurationManager::class.java.classLoader,
            arrayOf(IShopConfigurationManager::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "toString" -> return@newProxyInstance "RecordingConfig"
                "hashCode" -> return@newProxyInstance 0
                "equals" -> return@newProxyInstance false
            }
            configReads += method.name
            when (method.name) {
                "getTseMode" -> mode
                "getTseConfig" -> TseConfigDto.TseConfig(timeAdminPin = "12345", mode = mode, failureBehavior = failureBehavior)
                else -> throw AssertionError("unexpected configuration call ${method.name}")
            }
        } as IShopConfigurationManager

    /** Never connected, health check answers nothing: the real service rejects with TSE_UNREACHABLE. */
    private fun unreachableDevice(): TseSignProvider =
        Proxy.newProxyInstance(
            TseSignProvider::class.java.classLoader,
            arrayOf(TseSignProvider::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "toString" -> return@newProxyInstance "UnreachableDevice"
                "hashCode" -> return@newProxyInstance 0
                "equals" -> return@newProxyInstance false
            }
            deviceCalls += method.name
            when (method.name) {
                "isConnected" -> false
                "checkHealth" -> null
                else -> throw AssertionError("unexpected device call ${method.name}")
            }
        } as TseSignProvider

    private inline fun <reified T> untouched(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            when (method.name) {
                "toString" -> "Untouched"
                "hashCode" -> 0
                "equals" -> false
                else -> throw AssertionError("unexpected call ${T::class.java.simpleName}.${method.name}")
            }
        } as T

    private companion object {
        /** The message `RealTseTransactionServiceImpl.ensureTseReachable` throws with. */
        const val UNREACHABLE_MESSAGE = "TSE Server is unavailable"
    }
}
