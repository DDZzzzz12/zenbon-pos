package org.example.project.di

import co.touchlab.kermit.Logger
import co.touchlab.kermit.LoggerConfig
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import org.example.project.core.auth.SessionRoleLoader
import org.example.project.core.data.database.LocalCacheAvailability
import org.example.project.core.data.database.secret.LocalSecretsMigration
import org.example.project.core.data.database.secret.SecretCipher
import org.example.project.core.domain.rules.TableAccessRuleEngine
import org.example.project.features.dashboard.presentation.viewmodel.PosTableDetailViewModel
import org.example.project.features.launch.presentation.TerminalLifecycleHandler
import org.example.project.features.launch.presentation.viewmodels.LaunchCoordinator
import org.example.project.features.voucher.presentation.viewmodel.VoucherCreationViewModel
import org.example.project.ui.auth.SessionState
import org.example.project.ui.auth.StartupPolicy
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.verify.ParameterTypeInjection
import org.koin.test.verify.verify
import kotlin.reflect.KClass

/**
 * Build-time verification of composeApp's Koin object graph.
 *
 * A widened constructor compiles fine and only fails when the screen that needs the
 * dependency opens — at runtime, on a till. This test closes that gap: it walks every
 * definition in the real desktop module list and checks that each of the constructor
 * parameters of the definition's declared type has a binding somewhere in the graph.
 *
 * It uses Koin's reflection-based [verify] rather than `checkModules`, deliberately:
 * `checkModules` *instantiates* every definition, which here would open a real SQLite
 * file, build a real Ktor client and run `ShopDataManager.getShopId()!!` inside the
 * `PrinterSettingsViewModel` definition — all of which fail for reasons that have
 * nothing to do with the graph being wired correctly. [verify] instantiates nothing.
 *
 * Note that `verifyAll(...)` on a list of modules checks each module **in isolation**
 * (a module plus only its `includes`), so a cross-module dependency — say
 * `NotificationReconciler` in `networkModule` needing `SyncService` from
 * `clientServiceModule` — is reported as missing when it is in fact bound. Wrapping the
 * whole list in one synthetic module via `includes(...)` verifies them as one graph,
 * which is what the running app actually assembles.
 *
 * The module list is the desktop one — [getAllModules] plus the JVM [getPlatformModules]
 * actual — because that is the target this test source set runs on. The Android and wasm
 * actuals of `databaseModule` / `getPlatformModules` are not reachable from here and are
 * therefore not covered.
 */
class KoinModuleGraphTest {
    /**
     * The desktop graph the app assembles in `Main.kt` (`initKoin`), as one module so it
     * is verified as a whole rather than module by module.
     */
    private fun desktopGraph(): Module = module { includes(getAllModules() + getPlatformModules()) }

    /**
     * Constructor parameters that are supplied at the definition site rather than injected,
     * declared per target type so the allowance never widens beyond the one definition it
     * is there for.
     *
     * - [LocalCacheAvailability] — `single { LocalCacheAvailability(isAvailable = true) }`
     * - [SessionState] — `single { startupSessionState(get(), get(), get()) }`, which builds it with
     *   `StartupPolicy.WIPE`
     * - [HttpClient] and [Logger] — third-party types built by a factory function
     *   (`createPosHttpClient`, `Logger.withTag`), so their own constructor parameters are
     *   not, and never will be, Koin bindings.
     * - [PosTableDetailViewModel] — its `shopId: () -> Long?` is the bound `ShopDataManager::getShopId`
     *   reference passed at the definition site (it reads the print-settings for „Referenzdruck“).
     * - [VoucherCreationViewModel] — the same `shopId` reference, for the voucher-sale receipt print.
     */
    private val injections: List<ParameterTypeInjection> =
        listOf(
            ParameterTypeInjection(LocalCacheAvailability::class, listOf(Boolean::class)),
            ParameterTypeInjection(SessionState::class, listOf(StartupPolicy::class)),
            ParameterTypeInjection(HttpClient::class, listOf(HttpClientEngine::class, HttpClientConfig::class)),
            ParameterTypeInjection(Logger::class, listOf(LoggerConfig::class, String::class)),
            ParameterTypeInjection(TerminalLifecycleHandler::class, listOf(Function0::class)),
            ParameterTypeInjection(PosTableDetailViewModel::class, listOf(Function0::class)),
            ParameterTypeInjection(VoucherCreationViewModel::class, listOf(Function0::class)),
        )

    /** Nothing is whitelisted globally — every allowance is scoped to a type in [injections]. */
    private val extraTypes: List<KClass<*>> = emptyList()

    @Test
    fun `every definition in the desktop graph has its dependencies bound`() {
        desktopGraph().verify(extraTypes = extraTypes, injections = injections)
    }

    /**
     * Guards the guard: [verify] passes vacuously over a type that has no definition at all,
     * so this pins the types whose constructors or bindings this branch changed. A probe
     * definition declares each as a constructor parameter, so verifying the graph plus the
     * probe fails by name if a binding is removed or mis-scoped. Like the rest of this class
     * it instantiates nothing, so the [SecretCipher] probe never touches the real keychain.
     *
     * [verify] treats a constructor parameter with a default value as satisfied, so the
     * defaulted IO dispatchers are not covered here.
     */
    @Test
    fun `the widened types are bound in the desktop graph`() {
        val probed = module { includes(desktopGraph()) } + module { single { GraphProbe(get(), get(), get(), get(), get()) } }
        module { includes(probed) }.verify(extraTypes = extraTypes, injections = injections)
    }

    /**
     * Exists only to make [TableAccessRuleEngine], [LaunchCoordinator], [SecretCipher], [LocalSecretsMigration] and
     * [SessionRoleLoader] required by a definition. The migration is resolved by `launchLocalSecretsMigration` at start-up
     * and the loader by `koinInject` in `SettingProvider`, not by a constructor, so nothing else would notice either
     * binding going missing.
     */
    @Suppress("unused")
    private class GraphProbe(
        val tableAccessRuleEngine: TableAccessRuleEngine,
        val launchCoordinator: LaunchCoordinator,
        val secretCipher: SecretCipher,
        val localSecretsMigration: LocalSecretsMigration,
        val sessionRoleLoader: SessionRoleLoader,
    )
}
