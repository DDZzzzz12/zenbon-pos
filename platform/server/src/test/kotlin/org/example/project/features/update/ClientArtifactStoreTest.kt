package org.example.project.features.update

import org.example.project.features.update.data.service.ClientArtifactStore
import org.example.project.features.update.data.service.UpdateStager
import org.example.project.features.update.domain.models.ClientBuildInfo
import org.example.project.infrastructure.errors.PosException
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ClientArtifactStoreTest {
    private val root: File = Files.createTempDirectory("client-store").toFile()
    private val dir = File(root, "client")
    private val store = ClientArtifactStore(dir, UpdateStager(File(root, "server-staging")))
    private val info = ClientBuildInfo(versionCode = 13, versionName = "1.2.0")

    @AfterTest fun tearDown() {
        root.deleteRecursively()
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sums(digest: String) = "$digest  zenbon-pos-v1.2.0.apk\n${"0".repeat(64)}  zenbon-pos-server-v1.2.0.zip\n"

    /** A downloaded-but-unverified file, placed where the stager puts it: inside [ClientArtifactStore.workDir]. */
    private fun downloaded(bytes: ByteArray): File {
        store.workDir.mkdirs()
        return File(store.workDir, "downloaded.apk").also { it.writeBytes(bytes) }
    }

    @Test fun `stages an apk whose digest matches the published one`() {
        val bytes = "apk-one".toByteArray()
        val staged = store.stage("1.2.0", info, "notes", downloaded(bytes), sha(bytes), sums(sha(bytes)))

        assertEquals(13, staged.versionCode)
        assertEquals(bytes.size.toLong(), staged.sizeBytes)
        assertEquals(staged, store.current())
        assertContentEquals(bytes, store.apkFile()!!.readBytes())
    }

    @Test fun `a digest mismatch writes nothing and leaves an earlier apk intact`() {
        val first = "apk-one".toByteArray()
        store.stage("1.1.0", info.copy(versionCode = 12), "n", downloaded(first), sha(first), sums(sha(first)))

        val tampered = "apk-two".toByteArray()
        assertFailsWith<PosException> {
            // published digest is for the real bytes; what arrived is different
            store.stage("1.2.0", info, "n", downloaded(tampered), sha(tampered), sums(sha("apk-real".toByteArray())))
        }

        assertEquals(12, store.current()!!.versionCode)
        assertContentEquals(first, store.apkFile()!!.readBytes())
    }

    @Test fun `a newer stage replaces the older one`() {
        val one = "apk-one".toByteArray()
        val two = "apk-two-longer".toByteArray()
        store.stage("1.1.0", info.copy(versionCode = 12), "n", downloaded(one), sha(one), sums(sha(one)))
        store.stage("1.2.0", info, "n", downloaded(two), sha(two), sums(sha(two)))

        assertEquals("1.2.0", store.current()!!.releaseVersion)
        assertContentEquals(two, store.apkFile()!!.readBytes())
    }

    @Test fun `a truncated apk is not offered`() {
        val bytes = "apk-one".toByteArray()
        store.stage("1.2.0", info, "n", downloaded(bytes), sha(bytes), sums(sha(bytes)))
        File(dir, "android-client.apk").writeBytes(bytes.copyOf(2))

        assertNull(store.current())
        assertNull(store.apkFile())
    }

    @Test fun `an empty store offers nothing`() {
        assertNull(store.current())
    }
}
