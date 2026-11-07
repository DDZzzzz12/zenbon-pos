package org.example.project.features.update

import io.ktor.http.HttpStatusCode
import org.example.project.features.update.data.service.ClientArtifactStore
import org.example.project.features.update.data.service.ClientUpdateServiceImpl
import org.example.project.features.update.data.service.UpdateStager
import org.example.project.features.update.domain.models.ClientBuildInfo
import org.example.project.infrastructure.errors.PosException
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ClientUpdateServiceImplTest {
    private val root: File = Files.createTempDirectory("client-service").toFile()
    private val store = ClientArtifactStore(File(root, "client"), UpdateStager(File(root, "server")))
    private val service = ClientUpdateServiceImpl(store)

    @AfterTest fun tearDown() {
        root.deleteRecursively()
    }

    private fun stageOne(): String {
        val bytes = "apk".toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        store.workDir.mkdirs()
        val file = File(store.workDir, "d.apk").also { it.writeBytes(bytes) }
        store.stage("1.2.0", ClientBuildInfo(13, "1.2.0"), "notes", file, digest, "$digest  zenbon-pos-v1.2.0.apk\n")
        return digest
    }

    @Test fun `nothing staged is a 404, not an empty answer`() {
        val e = assertFailsWith<PosException> { service.androidClient() }
        assertEquals(HttpStatusCode.NotFound, e.httpStatus)
        assertFailsWith<PosException> { service.androidClientDownload() }
    }

    @Test fun `describes the staged build`() {
        val digest = stageOne()
        val dto = service.androidClient()
        assertEquals(13, dto.versionCode)
        assertEquals("1.2.0", dto.versionName)
        assertEquals(digest, dto.sha256)
        assertEquals(3L, dto.sizeBytes)
        assertEquals("notes", dto.releaseNotes)
    }

    @Test fun `the download carries the file and its digest`() {
        val digest = stageOne()
        val download = service.androidClientDownload()
        assertEquals(digest, download.sha256)
        assertEquals("apk", download.file.readText())
    }
}
