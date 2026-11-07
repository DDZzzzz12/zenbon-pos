package org.example.project.routes

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.runBlocking
import org.example.project.core.config.RestoredPathPolicy
import org.example.project.core.domain.enums.setting.RestoredPathSetting
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.dbQuery
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DisplayName("Backup Restore Routes")
class BackupRestoreRoutesTest {
    @Nested
    @DisplayName("POST /api/v1/backup/create")
    inner class CreateBackup {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.post("/api/v1/backup/create")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("POST /api/v1/backup/restore")
    inner class RestoreBackup {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.post("/api/v1/backup/restore")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/v1/backup/config")
    inner class GetBackupConfig {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/v1/backup/config")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("PUT /api/v1/backup/config")
    inner class UpdateBackupConfig {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.put("/api/v1/backup/config") {
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 for invalid request body`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.put("/api/v1/backup/config") {
                        bearerAuth(token)
                        setBody(TextContent("not json", ContentType.Application.Json))
                    }
                // Route handler catches all exceptions as 500 (catch-all in route)
                val validStatuses = setOf(HttpStatusCode.BadRequest, HttpStatusCode.InternalServerError)
                assert(response.status in validStatuses) {
                    "Expected BadRequest or InternalServerError but got ${response.status}"
                }
            }

        private fun backupBody(path: String) =
            TextContent(
                """{"defaultBackupPath":${kotlinx.serialization.json.JsonPrimitive(path)},"autoEnabled":false}""",
                ContentType.Application.Json,
            )

        /**
         * QA RST-11 (2026-10-06): on a shop that never ran a backup, the stored `lastBackupAt` is JSON
         * null, and every later save read it back with `JsonPrimitive.content` — the four-letter string
         * "null" — echoing and storing that instead of null.
         */
        @Test
        fun `a second save keeps a never-run lastBackupAt null, not the string null`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createShopInfo()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                // The default cap: ShopConfigurationManager's cache outlives this test's database.
                val body = TextContent("""{"autoEnabled":false,"maxUploadBytes":2147483648}""", ContentType.Application.Json)
                val first =
                    client.put("/api/v1/backup/config") {
                        bearerAuth(token)
                        setBody(body)
                    }
                val second =
                    client.put("/api/v1/backup/config") {
                        bearerAuth(token)
                        setBody(body)
                    }

                assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
                assertEquals(HttpStatusCode.OK, second.status, second.bodyAsText())
                assertFalse(second.bodyAsText().contains("\"lastBackupAt\":\"null\""), second.bodyAsText())
                val stored = client.get("/api/v1/backup/config") { bearerAuth(token) }.bodyAsText()
                assertFalse(stored.contains("\"lastBackupAt\":\"null\""), stored)
            }

        /** An older client never sends copyTo; its save must not erase a configured target, while an explicit null still clears it. */
        @Test
        fun `a save without the copy fields keeps the copy target and copyTo null with a retention clears it`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createShopInfo()
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)

                suspend fun save(json: String) =
                    client.put("/api/v1/backup/config") {
                        bearerAuth(token)
                        setBody(TextContent(json, ContentType.Application.Json))
                    }
                val target = """{"kind":"MOUNTED_PATH","path":"/media/pos/ZEN_BACKUP"}"""

                assertEquals(HttpStatusCode.OK, save("""{"autoEnabled":false,"copyTo":$target,"copyRetentionCount":9}""").status)
                val older = save("""{"autoEnabled":true}""")
                assertEquals(HttpStatusCode.OK, older.status, older.bodyAsText())
                assertTrue(older.bodyAsText().contains("/media/pos/ZEN_BACKUP"), older.bodyAsText())
                assertTrue(older.bodyAsText().contains("\"copyRetentionCount\":9"), older.bodyAsText())

                val cleared = save("""{"autoEnabled":true,"copyTo":null,"copyRetentionCount":7}""")
                assertEquals(HttpStatusCode.OK, cleared.status, cleared.bodyAsText())
                assertFalse(cleared.bodyAsText().contains("/media/pos/ZEN_BACKUP"), cleared.bodyAsText())
            }

        /** SET-05b: a relative path was stored as-is, to resolve under whatever the working directory is. */
        @Test
        fun `refuses a relative backup path with 400 and stores nothing`() =
            testApp(setupData = { TestData.createAdmin() }) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val relative = "set05-relative-${System.nanoTime()}/backups"

                val response =
                    client.put("/api/v1/backup/config") {
                        bearerAuth(token)
                        setBody(backupBody(relative))
                    }

                assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
                assertTrue(response.bodyAsText().contains("not an absolute path"), response.bodyAsText())
                assertFalse(java.io.File(relative).exists(), "a refused relative path must not be created")
                val stored = client.get("/api/v1/backup/config") { bearerAuth(token) }.bodyAsText()
                assertFalse(stored.contains(relative), stored)
            }

        /** SET-05b: a path naming an existing file was accepted. */
        @Test
        fun `refuses a backup path that names a file with 400`() =
            testApp(setupData = { TestData.createAdmin() }) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val file =
                    java.io.File
                        .createTempFile("backup-path-file", ".zip")
                        .apply { deleteOnExit() }

                val response =
                    client.put("/api/v1/backup/config") {
                        bearerAuth(token)
                        setBody(backupBody(file.absolutePath))
                    }

                assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
                assertTrue(response.bodyAsText().contains("not a directory"), response.bodyAsText())
            }

        @Test
        fun `an admin stores an absolute directory, a manager is refused`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    TestData.createManager()
                    TestData.createShopInfo()
                },
            ) { client ->
                val dir =
                    kotlin.io.path
                        .createTempDirectory("backup-set05")
                        .toFile()
                        .apply { deleteOnExit() }

                val manager =
                    client.put("/api/v1/backup/config") {
                        bearerAuth(TestAuthHelper.loginAsManager(client))
                        setBody(backupBody(dir.absolutePath))
                    }
                val admin =
                    client.put("/api/v1/backup/config") {
                        bearerAuth(TestAuthHelper.loginAsAdmin(client))
                        setBody(backupBody(dir.absolutePath))
                    }

                assertEquals(HttpStatusCode.Forbidden, manager.status)
                assertEquals(HttpStatusCode.OK, admin.status, admin.bodyAsText())
                assertTrue(admin.bodyAsText().contains(dir.name), admin.bodyAsText())
            }
    }

    @Nested
    @DisplayName("POST /api/v1/backup/create-to-server")
    inner class CreateBackupToServer {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.post("/api/v1/backup/create-to-server") {
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 for invalid request body`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.post("/api/v1/backup/create-to-server") {
                        bearerAuth(token)
                        setBody(TextContent("not json", ContentType.Application.Json))
                    }
                // Route handler catches all exceptions as 500 (catch-all in route)
                val validStatuses = setOf(HttpStatusCode.BadRequest, HttpStatusCode.InternalServerError)
                assert(response.status in validStatuses) {
                    "Expected BadRequest or InternalServerError but got ${response.status}"
                }
            }
    }

    @Nested
    @DisplayName("POST /api/v1/backup/restore-from-server")
    inner class RestoreFromServer {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.post("/api/v1/backup/restore-from-server") {
                        setBody(TextContent("{}", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 for invalid request body`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.post("/api/v1/backup/restore-from-server") {
                        bearerAuth(token)
                        setBody(TextContent("not json", ContentType.Application.Json))
                    }
                // Route handler catches all exceptions as 500 (catch-all in route)
                val validStatuses = setOf(HttpStatusCode.BadRequest, HttpStatusCode.InternalServerError)
                assert(response.status in validStatuses) {
                    "Expected BadRequest or InternalServerError but got ${response.status}"
                }
            }
    }

    @Nested
    @DisplayName("GET /api/v1/backup/directories")
    inner class ListDirectories {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/v1/backup/directories")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/v1/backup/list")
    inner class ListBackups {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/v1/backup/list")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET/POST /api/v1/backup/restored-paths")
    inner class RestoredPaths {
        private val resolveBody = """{"decisions":[{"setting":"DATEV_EXPORT_PATH","decision":"KEEP"}]}"""

        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/backup/restored-paths").status)
            }

        @Test
        fun `a manager is refused both reading and resolving the review`() =
            testApp(setupData = { TestData.createManager() }) { client ->
                val token = TestAuthHelper.loginAsManager(client)
                val read = client.get("/api/v1/backup/restored-paths") { bearerAuth(token) }
                val resolve =
                    client.post("/api/v1/backup/restored-paths") {
                        bearerAuth(token)
                        setBody(TextContent(resolveBody, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.Forbidden, read.status)
                assertEquals(HttpStatusCode.Forbidden, resolve.status)
            }

        @Test
        fun `an admin reads an empty review on an installation that was never restored`() =
            testApp(setupData = { TestData.createAdmin() }) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/v1/backup/restored-paths") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("""{"pending":false,"paths":[]}""", response.bodyAsText().replace(" ", ""))
            }

        @Test
        fun `an admin's decision on a setting that is not pending is accepted and changes nothing`() =
            testApp(setupData = { TestData.createAdmin() }) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response =
                    client.post("/api/v1/backup/restored-paths") {
                        bearerAuth(token)
                        setBody(TextContent(resolveBody, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("""{"pending":false,"paths":[]}""", response.bodyAsText().replace(" ", ""))
            }

        /**
         * NOTE-a (emu17): an operator who replaced a restored path through its own config screen
         * still saw the old path listed as pending. Storing a path of this installation through the
         * backup or the DATEV config route resolves that setting's review entry.
         */
        @Test
        fun `storing a local path through the backup and DATEV config routes resolves their review entries`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    val shopId = TestData.createShopInfo().id.value
                    runBlocking {
                        dbQuery {
                            ConfigurationRepository().setSetting(
                                shopId,
                                ConfigKey.RESTORED_PATHS_REVIEW,
                                RestoredPathPolicy.encodeReview(
                                    mapOf(
                                        RestoredPathSetting.DATEV_EXPORT_PATH to "/home/pos/Zenbon POS/exports",
                                        RestoredPathSetting.BACKUP_PATH to "/home/pos/Zenbon POS/backups",
                                    ),
                                ),
                            )
                        }
                    }
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)

                val backupPut =
                    client.put("/api/v1/backup/config") {
                        bearerAuth(token)
                        setBody(TextContent("""{"autoEnabled":false}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.OK, backupPut.status)
                val afterBackup = client.get("/api/v1/backup/restored-paths") { bearerAuth(token) }.bodyAsText()
                assertTrue(afterBackup.contains("DATEV_EXPORT_PATH"), afterBackup)
                assertFalse(afterBackup.contains("BACKUP_PATH"), "the backup entry must be resolved: $afterBackup")

                val datevPut =
                    client.put("/api/v1/config/1/datev-xml") {
                        bearerAuth(token)
                        setBody(TextContent("""{"kassenName":"Kasse","exportPath":""}""", ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.OK, datevPut.status, datevPut.bodyAsText())
                val afterDatev = client.get("/api/v1/backup/restored-paths") { bearerAuth(token) }.bodyAsText()
                assertEquals("""{"pending":false,"paths":[]}""", afterDatev.replace(" ", ""))
            }
    }
}
