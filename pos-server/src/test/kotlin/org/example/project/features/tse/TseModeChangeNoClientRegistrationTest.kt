package org.example.project.features.tse

import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.features.terminal.data.repository.TerminalRepositoryImpl
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.tse.data.service.TseConfigurationServiceImpl
import org.example.project.features.tse.domain.model.TseInitResult
import org.example.project.features.tse.domain.provider.TseAdminProvider
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * F41: switching the TSE mode on an initialized TSE used to call `registerClient` for the terminal
 * bound to the calling device — swallowing any failure at debug level and never setting the
 * terminal's `tseClientRegistered` flag. A client is registered only by setup and the explicit
 * `tse-register`; a mode change just notifies the provider of the new mode.
 */
class TseModeChangeNoClientRegistrationTest {
    private val deviceId = "device-f41"
    private val clientId = "TER-F41"

    @Test
    fun `a mode change on an initialized TSE notifies the mode but registers no client`() =
        testApp(setupData = {
            TestData.setTseMode(TseMode.SIMULATION)
            seedUnregisteredTerminal()
        }) {
            val calls = mutableListOf<Pair<String, List<Any?>>>()
            val service =
                TseConfigurationServiceImpl(
                    tseAdminProvider = recordingProvider(calls),
                    terminalRepository = TerminalRepositoryImpl(),
                    reloadProviders = { TseInitResult.Operational },
                )

            val result = service.updateTseConfig(TseConfigDto.UpdateTseConfigRequest(mode = TseMode.NORMAL), deviceId)

            assertEquals(TseConfigDto.TseModeApplyStatus.OPERATIONAL, result.applyStatus)
            val names = calls.map { it.first }
            assertFalse("registerClient" in names, "a mode change must not register a TSE client; calls were $names")
            assertEquals(listOf("NORMAL", clientId), calls.single { it.first == "setMode" }.second)
            assertFalse(transaction { Terminal.all().single().tseClientRegistered })
        }

    private fun seedUnregisteredTerminal() {
        transaction {
            Terminal.new {
                this.deviceId = this@TseModeChangeNoClientRegistrationTest.deviceId
                this.clientId = this@TseModeChangeNoClientRegistrationTest.clientId
                this.role = "CASHIER"
                this.fiscal = true
                this.tseClientRegistered = false
                this.isActive = true
                this.registeredAt = currentTimeStamp()
                this.lastSeenAt = currentTimeStamp()
            }
        }
    }

    /**
     * A [TseAdminProvider] that records every call (name + arguments, minus the continuation) and
     * answers only what a mode change needs; anything else fails the test.
     */
    private fun recordingProvider(calls: MutableList<Pair<String, List<Any?>>>): TseAdminProvider =
        Proxy.newProxyInstance(
            TseAdminProvider::class.java.classLoader,
            arrayOf(TseAdminProvider::class.java),
        ) { _, method, args ->
            val realArgs = args.orEmpty().dropLast(1) // suspend functions take a trailing Continuation
            calls += method.name to realArgs
            when (method.name) {
                "isInitialized", "setMode" -> true
                "refreshConfig", "registerClient" -> Unit
                else -> throw AssertionError("unexpected TseAdminProvider.${method.name}")
            }
        } as TseAdminProvider
}
