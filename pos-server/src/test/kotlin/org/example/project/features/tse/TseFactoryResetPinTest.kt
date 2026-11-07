package org.example.project.features.tse

import kotlinx.coroutines.runBlocking
import org.example.project.core.error.PosErrorCode
import org.example.project.features.terminal.data.repository.TerminalRepositoryImpl
import org.example.project.features.terminal.data.service.TerminalCapabilityResolver
import org.example.project.features.tse.data.provider.local.LocalTseAdminProvider
import org.example.project.features.tse.data.provider.local.LocalTseLifecycleManager
import org.example.project.features.tse.domain.session.TseAdminSession
import org.example.project.infrastructure.errors.PosException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse

/**
 * The factory reset logs in as nobody: the driver's operation checks for development firmware and calls
 * `tse_factoryReset()`, which the SDK accepts without a PIN. The provider used to demand an unlocked
 * administration session anyway, so a development TSE whose PIN was lost could not be reset.
 */
class TseFactoryResetPinTest {
    private val repository = TerminalRepositoryImpl()
    private val provider =
        LocalTseAdminProvider(
            lifecycleManager =
                LocalTseLifecycleManager(
                    configSource = { error("not used") },
                    locate = { null },
                ),
            terminalRepository = repository,
            capabilityResolver = TerminalCapabilityResolver(repository),
        )

    @BeforeEach
    fun lock() = TseAdminSession.clear()

    @AfterEach
    fun cleanUp() = TseAdminSession.clear()

    @Test
    fun `a locked administration session does not stop the factory reset`() {
        val failure = runCatching { runBlocking { provider.factoryReset() } }.exceptionOrNull()

        val lockedRefusal = failure is PosException && failure.code == PosErrorCode.TSE_ADMIN_LOCKED
        assertFalse(lockedRefusal, "factory reset must not ask for the Admin PIN, saw: $failure")
    }
}
