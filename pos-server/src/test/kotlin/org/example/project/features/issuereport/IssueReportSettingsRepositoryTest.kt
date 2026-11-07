package org.example.project.features.issuereport

import kotlinx.coroutines.test.runTest
import org.example.project.features.issuereport.data.repository.IssueReportSettingsRepositoryImpl
import org.example.project.features.update.data.crypto.TokenCipher
import org.example.project.testutils.TestDatabase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("IssueReportSettingsRepository")
class IssueReportSettingsRepositoryTest {
    private val key = TokenCipher.generateKeyBase64()
    private lateinit var repo: IssueReportSettingsRepositoryImpl

    @BeforeEach
    fun setUp() {
        TestDatabase.setup()
        repo = IssueReportSettingsRepositoryImpl(TokenCipher(key))
    }

    @AfterEach
    fun tearDown() {
        TestDatabase.teardown()
    }

    @Test
    fun `a fresh row has no overrides and no token`() =
        runTest {
            val s = repo.load()
            assertNull(s.owner)
            assertNull(s.repo)
            assertNull(s.isPublic)
            assertNull(s.token)
            assertFalse(s.tokenUndecryptable)
            assertEquals("https://api.github.com", s.apiBaseUrl)
            assertFalse(s.useUpdateToken, "reusing the update token is an explicit opt-in, off by default")
        }

    @Test
    fun `the update-token choice round-trips and leaves the own token alone`() =
        runTest {
            repo.setToken("github_pat_secret")
            repo.saveTarget("acme", "pos", "https://api.github.com", isPublic = false, useUpdateToken = true)
            val on = repo.load()
            assertTrue(on.useUpdateToken)
            assertEquals("github_pat_secret", on.token)

            repo.saveTarget("acme", "pos", "https://api.github.com", isPublic = false, useUpdateToken = false)
            val off = repo.load()
            assertFalse(off.useUpdateToken)
            assertEquals("github_pat_secret", off.token)
        }

    @Test
    fun `saves a target without touching the token`() =
        runTest {
            repo.setToken("github_pat_secret")
            repo.saveTarget("acme", "pos", "https://api.github.com", isPublic = true, useUpdateToken = false)
            val s = repo.load()
            assertEquals("acme", s.owner)
            assertEquals("pos", s.repo)
            assertEquals(true, s.isPublic)
            assertEquals("github_pat_secret", s.token)
        }

    @Test
    fun `the stored column never contains the plaintext token`() =
        runTest {
            repo.setToken("github_pat_secret")
            assertFalse(repo.rawCiphertextForTest()!!.contains("github_pat"))
        }

    @Test
    fun `a token sealed by another host's key reads as undecryptable not unset`() =
        runTest {
            repo.setToken("github_pat_secret")
            val other = IssueReportSettingsRepositoryImpl(TokenCipher(TokenCipher.generateKeyBase64()))
            val s = other.load()
            assertNull(s.token)
            assertTrue(s.tokenUndecryptable)
        }

    @Test
    fun `clearing removes the token completely`() =
        runTest {
            repo.setToken("github_pat_secret")
            repo.clearToken()
            val s = repo.load()
            assertNull(s.token)
            assertFalse(s.tokenUndecryptable)
            assertNull(s.tokenSetAt)
        }

    @Test
    fun `storing a blank token is refused`() =
        runTest {
            val ex = runCatching { repo.setToken("  ") }.exceptionOrNull()
            assertTrue(ex is IllegalArgumentException)
        }
}
