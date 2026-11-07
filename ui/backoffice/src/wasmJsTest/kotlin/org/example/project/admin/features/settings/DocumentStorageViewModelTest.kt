package org.example.project.admin.features.settings

import kotlinx.coroutines.test.runTest
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.settings.presentation.sections.DocumentStorageField
import org.example.project.admin.features.settings.presentation.sections.DocumentStorageState
import org.example.project.admin.features.settings.presentation.sections.DocumentStorageViewModel
import org.example.project.core.domain.dto.backup.BackupRestoreDto
import org.example.project.core.network.error.NetworkApiException
import org.example.project.core.presentation.error.UiError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun entry(
    name: String,
    path: String,
    isDirectory: Boolean = true,
    isWritable: Boolean = true,
) = BackupRestoreDto.DirectoryEntryDto(
    name = name,
    path = path,
    isDirectory = isDirectory,
    isReadable = true,
    isWritable = isWritable,
)

class DocumentStorageViewModelTest {
    private fun vm(config: FakeShopConfigApiClient = FakeShopConfigApiClient()) = DocumentStorageViewModel(config, config)

    @Test
    fun refresh_populatesEveryField() =
        runTest {
            val model = vm()
            model.refresh()
            assertEquals("/var/pos/documents", model.state.path)
            assertEquals("20", model.state.maxUploadSizeInput)
            assertEquals(listOf("INVOICE", "RECEIPT"), model.state.defaultDocumentTypes)
            assertEquals(listOf("Mietvertrag"), model.state.additionalDocumentTypes)
            assertEquals(false, model.state.isLoading)
            assertNull(model.state.error)
        }

    @Test
    fun refreshFailure_yieldsNetworkError() =
        runTest {
            val model = vm(FakeShopConfigApiClient(onCall = { throw NetworkApiException(RuntimeException("down")) }))
            model.refresh()
            assertIs<UiError.Network>(model.state.error)
        }

    @Test
    fun addDocumentType_trimsAndClearsTheField() =
        runTest {
            val model = vm()
            model.refresh()
            model.updateNewDocumentType("  Kaufvertrag  ")
            model.addDocumentType()
            assertEquals(listOf("Mietvertrag", "Kaufvertrag"), model.state.additionalDocumentTypes)
            assertEquals("", model.state.newDocumentType)
        }

    @Test
    fun addDocumentType_rejectsDuplicatesAgainstBothLists() =
        runTest {
            val model = vm()
            model.refresh()
            model.updateNewDocumentType("Mietvertrag")
            assertEquals(AdminStringKeys.SettingsDocStorageTypeDuplicate, model.state.newDocumentTypeError)
            assertEquals(false, model.state.canAddDocumentType)
            model.addDocumentType()
            assertEquals(listOf("Mietvertrag"), model.state.additionalDocumentTypes)

            model.updateNewDocumentType("INVOICE")
            assertEquals(AdminStringKeys.SettingsDocStorageTypeDuplicate, model.state.newDocumentTypeError)
        }

    @Test
    fun addDocumentType_rejectsPathSeparatorsAndOverlongNames() =
        runTest {
            val model = vm()
            model.refresh()
            model.updateNewDocumentType("a/b")
            assertEquals(AdminStringKeys.SettingsDocStorageTypeSeparator, model.state.newDocumentTypeError)
            model.addDocumentType()
            assertEquals(listOf("Mietvertrag"), model.state.additionalDocumentTypes)

            model.updateNewDocumentType("x".repeat(51))
            assertEquals(AdminStringKeys.SettingsDocStorageTypeTooLong, model.state.newDocumentTypeError)
        }

    @Test
    fun removeDocumentType_dropsIt() =
        runTest {
            val model = vm()
            model.refresh()
            model.removeDocumentType("Mietvertrag")
            assertTrue(model.state.additionalDocumentTypes.isEmpty())
        }

    @Test
    fun uploadSizeOutsideOneToHundred_isRefusedOnSaveButNeverDisablesIt() =
        runTest {
            val config = FakeShopConfigApiClient()
            val model = vm(config)
            model.refresh()
            for (bad in listOf("0", "101", "not a number", "")) {
                model.updateMaxUploadSize(bad)
                // Option A: validity never disables Save; pressing it validates instead.
                assertEquals(true, model.state.canSave, "input '$bad'")
                assertEquals(
                    mapOf(DocumentStorageField.MAX_UPLOAD_SIZE to AdminStringKeys.SettingsDocStorageMaxUploadInvalid),
                    model.state.fieldErrors,
                )
                model.save()
                assertTrue(config.storageRequests.isEmpty())
            }
            model.updateMaxUploadSize("50")
            assertTrue(model.state.fieldErrors.isEmpty())
            assertEquals(true, model.state.canSave)
        }

    @Test
    fun canSave_isFalseOnlyWhileLoadingOrSaving() {
        val state = DocumentStorageState(maxUploadSizeInput = "x")
        assertEquals(true, state.canSave)
        assertEquals(false, state.copy(isLoading = true).canSave)
        assertEquals(false, state.copy(isSaving = true).canSave)
    }

    @Test
    fun save_sendsPathSizeAndTypes() =
        runTest {
            val config = FakeShopConfigApiClient()
            val model = vm(config)
            model.refresh()
            model.updatePath("/srv/belege")
            model.updateMaxUploadSize("35")
            model.updateNewDocumentType("Kaufvertrag")
            model.addDocumentType()
            model.save()
            val sent = config.storageRequests.single()
            assertEquals("/srv/belege", sent.path)
            assertEquals(35, sent.maxUploadSizeMb)
            assertEquals(listOf("Mietvertrag", "Kaufvertrag"), sent.additionalDocumentTypes)
            assertEquals(AdminStringKeys.SettingsDocStorageSaved, model.state.successMessage)
            assertEquals(false, model.state.isSaving)
        }

    @Test
    fun openBrowser_listsTheHomeDirectoryAndKeepsOnlyFolders() =
        runTest {
            val config =
                FakeShopConfigApiClient(
                    listings =
                        mapOf(
                            null to
                                BackupRestoreDto.DirectoryListingDto(
                                    currentPath = "/srv",
                                    parentPath = "/",
                                    entries = listOf(entry("belege", "/srv/belege"), entry("notes.txt", "/srv/notes.txt", isDirectory = false)),
                                    canWrite = true,
                                ),
                        ),
                )
            val model = vm(config)
            model.openBrowser()
            val browser = assertNotNull(model.state.browser)
            assertEquals("/srv", browser.currentPath)
            assertEquals(listOf("belege"), browser.entries.map { it.name })
            assertEquals(true, browser.canWrite)
            assertEquals(listOf<String?>(null), config.listedPaths)
        }

    @Test
    fun openBrowser_startsAtTheConfiguredPath() =
        runTest {
            val config =
                FakeShopConfigApiClient(
                    listings =
                        mapOf(
                            "/var/pos/documents" to
                                BackupRestoreDto.DirectoryListingDto(
                                    currentPath = "/var/pos/documents",
                                    parentPath = "/var/pos",
                                    entries = emptyList(),
                                    canWrite = true,
                                ),
                        ),
                )
            val model = vm(config)
            model.refresh()
            model.openBrowser()
            assertEquals(listOf<String?>("/var/pos/documents"), config.listedPaths)
            assertEquals("/var/pos/documents", assertNotNull(model.state.browser).currentPath)
            assertNull(model.state.error)
        }

    @Test
    fun openBrowser_fallsBackToTheDefaultDirectoryWhenTheConfiguredPathFails() =
        runTest {
            val config =
                FakeShopConfigApiClient(
                    listings =
                        mapOf(
                            null to
                                BackupRestoreDto.DirectoryListingDto(
                                    currentPath = "/home/pos",
                                    parentPath = "/home",
                                    entries = emptyList(),
                                    canWrite = true,
                                ),
                        ),
                    failingListPaths = setOf("/var/pos/documents"),
                )
            val model = vm(config)
            model.refresh()
            model.openBrowser()
            assertEquals(listOf<String?>("/var/pos/documents", null), config.listedPaths)
            assertEquals("/home/pos", assertNotNull(model.state.browser).currentPath)
            assertNull(model.state.error)
        }

    @Test
    fun selectDirectory_setsThePathAndClosesTheBrowser() =
        runTest {
            val model = vm()
            model.openBrowser()
            model.selectDirectory("/srv/belege")
            assertEquals("/srv/belege", model.state.path)
            assertNull(model.state.browser)
        }

    @Test
    fun navigateToParent_usesTheParentFromTheLastListing() =
        runTest {
            val config =
                FakeShopConfigApiClient(
                    listings =
                        mapOf(
                            null to
                                BackupRestoreDto.DirectoryListingDto(
                                    currentPath = "/srv/belege",
                                    parentPath = "/srv",
                                    entries = emptyList(),
                                    canWrite = true,
                                ),
                            "/srv" to
                                BackupRestoreDto.DirectoryListingDto(
                                    currentPath = "/srv",
                                    parentPath = "/",
                                    entries = listOf(entry("belege", "/srv/belege")),
                                    canWrite = true,
                                ),
                        ),
                )
            val model = vm(config)
            model.openBrowser()
            model.navigateToParent()
            assertEquals("/srv", assertNotNull(model.state.browser).currentPath)
            assertEquals(listOf<String?>(null, "/srv"), config.listedPaths)
        }

    @Test
    fun browseFailure_surfacesTheErrorInsteadOfSwallowingIt() =
        runTest {
            // The deleted composeApp ViewModel swallowed this with an empty onError, leaving a dialog
            // that silently showed nothing. A refused ADMIN-only listing must be visible.
            var calls = 0
            val config = FakeShopConfigApiClient(onCall = { if (++calls > 0) throw NetworkApiException(RuntimeException("denied")) })
            val model = vm(config)
            model.openBrowser()
            assertIs<UiError.Network>(model.state.error)
            val browser = assertNotNull(model.state.browser)
            assertEquals(false, browser.isLoading)
        }

    @Test
    fun browseFailure_retriesTheListingNotTheConfigLoad() =
        runTest {
            // One error surface serves three operations; retrying a failed listing must re-list the
            // directory the operator was in, not reload the storage config.
            var fail = false
            val config =
                FakeShopConfigApiClient(
                    listings =
                        mapOf(
                            null to
                                BackupRestoreDto.DirectoryListingDto(
                                    currentPath = "/srv",
                                    parentPath = "/",
                                    entries = listOf(entry("belege", "/srv/belege")),
                                    canWrite = true,
                                ),
                            "/srv/belege" to
                                BackupRestoreDto.DirectoryListingDto(
                                    currentPath = "/srv/belege",
                                    parentPath = "/srv",
                                    entries = emptyList(),
                                    canWrite = true,
                                ),
                        ),
                    onCall = { if (fail) throw NetworkApiException(RuntimeException("denied")) },
                )
            val model = vm(config)
            model.openBrowser()
            fail = true
            model.browseTo("/srv/belege")
            assertIs<UiError.Network>(model.state.error)

            fail = false
            model.retryFailedAction()
            assertEquals(listOf<String?>(null, "/srv/belege"), config.listedPaths)
            assertEquals("/srv/belege", assertNotNull(model.state.browser).currentPath)
        }

    @Test
    fun saveWithAnInvalidUploadSize_bumpsTheValidationAttemptAndSendsNothing() =
        runTest {
            val config = FakeShopConfigApiClient()
            val model = vm(config)
            model.refresh()
            model.updateMaxUploadSize("0")
            val dirtyBefore = model.state.isDirty
            model.save()
            assertEquals(1, model.state.validationAttempt)
            model.save()
            assertEquals(2, model.state.validationAttempt)
            // The field carries the message; no form-level error dialog any more.
            assertNull(model.state.error)
            assertEquals(dirtyBefore, model.state.isDirty)
            assertTrue(config.storageRequests.isEmpty())
        }
}
