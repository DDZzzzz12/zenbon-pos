package org.example.project.features.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.example.project.core.utils.SemanticVersion
import org.example.project.features.update.data.remote.GithubReleaseClient
import org.example.project.features.update.data.service.AndroidClientStager
import org.example.project.features.update.data.service.ClientArtifactStore
import org.example.project.features.update.data.service.UpdateStager
import org.example.project.features.update.domain.models.AvailableUpdate
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidClientStagerTest {
    private val root: File = Files.createTempDirectory("client-stager").toFile()
    private val store = ClientArtifactStore(File(root, "client"), UpdateStager(File(root, "server")))
    private val repo = GithubReleaseClient.Repo("https://api.github.com", "o", "r")
    private val apk = "the-android-build".toByteArray()
    private val requested = mutableListOf<String>()

    @AfterTest fun tearDown() {
        root.deleteRecursively()
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun update(
        apkId: Long? = 44,
        infoId: Long? = 45,
        checksumId: Long? = 43,
    ) = AvailableUpdate(
        version = SemanticVersion.parse("1.2.0")!!,
        releaseNotes = "release notes",
        serverAssetId = 42,
        checksumAssetId = checksumId,
        sizeBytes = 1024,
        apkAssetId = apkId,
        clientInfoAssetId = infoId,
    )

    private fun stager(
        sums: String = "${sha(apk)}  zenbon-pos-v1.2.0.apk\n",
        infoJson: String = """{"versionCode":13,"versionName":"1.2.0"}""",
        served: ByteArray = apk,
    ): AndroidClientStager {
        val engine =
            MockEngine { request ->
                val id = request.url.encodedPath.substringAfterLast('/')
                requested += id
                val octet = headersOf(HttpHeaders.ContentType, "application/octet-stream")
                when (id) {
                    "43" -> respond(sums, HttpStatusCode.OK, octet)
                    "44" -> respond(served, HttpStatusCode.OK, octet)
                    "45" -> respond(infoJson, HttpStatusCode.OK, octet)
                    else -> respondError(HttpStatusCode.NotFound)
                }
            }
        return AndroidClientStager(GithubReleaseClient(HttpClient(engine)), store)
    }

    @Test fun `stages the apk when the digest matches`() =
        runTest {
            stager().stage(repo, null, update())

            val staged = store.current()!!
            assertEquals(13, staged.versionCode)
            assertEquals("1.2.0", staged.releaseVersion)
            assertEquals("release notes", staged.releaseNotes)
            assertContentEquals(apk, store.apkFile()!!.readBytes())
        }

    @Test fun `a digest mismatch stages nothing and does not throw`() =
        runTest {
            stager(served = "something-else".toByteArray()).stage(repo, null, update())

            assertNull(store.current())
            assertEquals(0, store.workDir.listFiles()?.size ?: 0)
        }

    @Test fun `a release without an apk stages nothing and downloads nothing`() =
        runTest {
            stager().stage(repo, null, update(apkId = null))

            assertNull(store.current())
            assertEquals(emptyList(), requested)
        }

    @Test fun `a release without android-client json stages nothing`() =
        runTest {
            stager().stage(repo, null, update(infoId = null))
            assertNull(store.current())
        }

    @Test fun `unreadable client info stages nothing and does not throw`() =
        runTest {
            stager(infoJson = "not json").stage(repo, null, update())
            assertNull(store.current())
        }

    @Test fun `the same release is not downloaded twice`() =
        runTest {
            val s = stager()
            s.stage(repo, null, update())
            requested.clear()
            s.stage(repo, null, update())

            assertEquals(emptyList(), requested)
        }
}
