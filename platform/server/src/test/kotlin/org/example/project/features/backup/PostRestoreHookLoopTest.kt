package org.example.project.features.backup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.database.LiveDataSourceProvider
import org.example.project.features.backup.data.service.BackupRestoreServiceImpl
import org.example.project.features.backup.domain.service.PostRestoreHook
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@DisplayName("Post-restore hook loop")
class PostRestoreHookLoopTest {
    private fun service(hooks: List<PostRestoreHook>) =
        BackupRestoreServiceImpl(
            ConfigurationRepository(),
            object : IShopConfigurationManager by ShopConfigurationManager {},
            LiveDataSourceProvider { error("the hook loop never touches the data source") },
            postRestoreHooks = hooks,
        ) { error("seeder is not used here") }

    @Test
    fun `a failing hook is logged and the next hook still runs`() {
        val calls = mutableListOf<String>()
        val hooks =
            listOf(
                PostRestoreHook {
                    calls += "first"
                    error("boom")
                },
                PostRestoreHook { calls += "second" },
            )

        runBlocking { service(hooks).runPostRestoreHooks() }

        assertEquals(listOf("first", "second"), calls)
    }

    @Test
    fun `cancellation is rethrown and stops later hooks`() {
        val calls = mutableListOf<String>()
        val hooks =
            listOf(
                PostRestoreHook {
                    calls += "first"
                    throw CancellationException("cancelled")
                },
                PostRestoreHook { calls += "second" },
            )

        assertFailsWith<CancellationException> { runBlocking { service(hooks).runPostRestoreHooks() } }
        assertEquals(listOf("first"), calls)
    }
}
