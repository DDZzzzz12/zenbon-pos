package org.example.project.features.tse

import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.features.tse.data.service.mapInitResult
import org.example.project.features.tse.domain.model.TseInitResult
import org.example.project.infrastructure.di.TSE_SDK_MISSING_REASON
import org.example.project.infrastructure.di.reloadTseProviders
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T9-15: on a server without the Swissbit SDK (the public build before the operator adds
 * `native/WormAPI.jar`), saving the TSE configuration answered 500 `NoClassDefFoundError` — the
 * config was already saved, but the provider reload resolved `LocalTseLifecycleManager`, whose
 * `TseStatus` field is typed on a Swissbit class.
 *
 * The test machine has the SDK, so the missing case is driven through `sdkPresent = false`; the
 * point is that the reload never reaches the SDK-typed manager and reports a usable result.
 */
class TseReloadWithoutSdkTest {
    @Test
    fun `a NORMAL config without the SDK reloads as unreachable, naming the SDK`() =
        testApp(setupData = { TestData.setTseMode(TseMode.NORMAL) }) {
            val result = reloadTseProviders(configure = true, sdkPresent = false)

            assertEquals(TseInitResult.Unreachable(TSE_SDK_MISSING_REASON), result)
            val dto = mapInitResult(TseConfigDto.TseConfig(mode = TseMode.NORMAL, timeAdminPin = "80417"), result)
            assertEquals(TseConfigDto.TseModeApplyStatus.UNREACHABLE, dto.applyStatus)
            assertTrue(dto.message.contains("WormAPI.jar"), dto.message)
        }

    @Test
    fun `a DISABLED config without the SDK reloads as disabled`() =
        testApp(setupData = { TestData.setTseMode(TseMode.DISABLED) }) {
            assertEquals(TseInitResult.Disabled, reloadTseProviders(configure = true, sdkPresent = false))
        }
}
