package org.example.project.features.update

import kotlinx.coroutines.test.runTest
import org.example.project.features.update.data.crypto.TokenCipher
import org.example.project.features.update.data.repository.UpdateSettingsRepositoryImpl
import org.example.project.features.update.domain.models.UpdateChannel
import org.example.project.testutils.TestDatabase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The three states the repository must keep apart: no token, a token this host can open, and a
 * token this host cannot open. Collapsing the last two -- reporting a backup-restored token as
 * simply "unset" -- is the defect these tests exist to prevent.
 */
@DisplayName("UpdateSettingsRepository")
class UpdateSettingsRepositoryTest {
    private val key = TokenCipher.generateKeyBase64()
    private lateinit var repo: UpdateSettingsRepositoryImpl

    @BeforeEach
    fun setUp() {
        TestDatabase.setup()
        repo = UpdateSettingsRepositoryImpl(TokenCipher(key))
    }

    @AfterEach
    fun tearDown() {
        TestDatabase.teardown()
    }

    @Test
    fun `defaults to the public channel with auto-apply off`() =
        runTest {
            val s = repo.load()
            assertEquals(UpdateChannel.PUBLIC, s.channel)
            assertEquals("zenbon-pos", s.channel.repoName, "the public channel names the open repository")
            assertFalse(s.autoApply)
            assertNull(s.token)
            assertFalse(s.tokenUndecryptable)
        }

    @Test
    fun `stores a token encrypted and reads it back decrypted`() =
        runTest {
            repo.setToken("github_pat_secret")
            assertEquals("github_pat_secret", repo.load().token)
        }

    @Test
    fun `the stored column never contains the plaintext token`() =
        runTest {
            repo.setToken("github_pat_secret")
            assertFalse(repo.rawCiphertextForTest()!!.contains("github_pat"))
        }

    @Test
    fun `a token sealed under another key reads back as undecryptable`() =
        runTest {
            repo.setToken("github_pat_secret")
            val otherHost = UpdateSettingsRepositoryImpl(TokenCipher(TokenCipher.generateKeyBase64()))
            val s = otherHost.load()
            assertNull(s.token)
            assertTrue(s.tokenUndecryptable, "a foreign-key token must be reported, not silently absent")
        }

    @Test
    fun `a host with no key at all reports undecryptable rather than unset`() =
        runTest {
            repo.setToken("github_pat_secret")
            val keyless = UpdateSettingsRepositoryImpl(TokenCipher(null))
            val s = keyless.load()
            assertNull(s.token)
            assertTrue(s.tokenUndecryptable)
        }

    @Test
    fun `clearing the token clears both columns`() =
        runTest {
            repo.setToken("github_pat_secret")
            repo.clearToken()
            val s = repo.load()
            assertNull(s.token)
            assertFalse(s.tokenUndecryptable)
            assertNull(repo.rawCiphertextForTest())
        }
}
