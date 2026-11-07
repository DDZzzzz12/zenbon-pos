package org.example.project.admin.features.settings.presentation.sections

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.launch
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.data.remote.AccountingConfigurationApiClient
import org.example.project.core.data.remote.ShopConfigurationApiClient
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi

/** adminApp is single-tenant, matching the other config ViewModels. */
private const val DEFAULT_STORAGE_SHOP_ID: Long = 1L

/**
 * ViewModel for the Belegablage section — server storage path, upload cap, and the custom document
 * types that sit beside the built-in `DocumentType` enum values.
 *
 * Restored from composeApp's deleted `DocumentStorageViewModel` (commit `498867657`), with two
 * deliberate divergences:
 *  - the directory browser goes through [ShopConfigurationApiClient.listDirectories] rather than a
 *    second `BackupApiClient` injection — the same endpoint is already on this client;
 *  - a failed listing surfaces a typed error instead of being swallowed, which left the original's
 *    dialog silently empty when the ADMIN-only endpoint refused.
 *
 * Save success is a `StringKey` consumed by `PosSuccessWatcher`, replacing the original's
 * `delay(2000)` flag reset.
 */
class DocumentStorageViewModel(
    private val shopConfigurationApiClient: ShopConfigurationApiClient,
    private val accountingConfigurationApiClient: AccountingConfigurationApiClient,
    private val shopId: Long = DEFAULT_STORAGE_SHOP_ID,
) : ViewModel() {
    private val log = Logger.withTag("document-storage")

    var state by mutableStateOf(DocumentStorageState())
        private set

    /**
     * The call that produced the current [DocumentStorageState.error]. The section shows one error
     * surface for three different operations, so a fixed retry would re-run the config load after a
     * *directory listing* failed and silently drop the folder the operator was browsing.
     *
     * Kept off the state on purpose: it is a callback the watcher invokes, never something the
     * composition reads, so it needs no snapshot backing and keeps [DocumentStorageState] pure data.
     */
    private var failedAction: (() -> Unit)? = null

    /** Re-runs whatever failed last — see [failedAction]. Wired to the section's `PosErrorWatcher`. */
    fun retryFailedAction() {
        failedAction?.invoke()
    }

    /** Driven by the section on entry — the VM is retained app-wide, so `init` would go stale. */
    fun refresh() {
        state = state.copy(isLoading = true, error = null)
        viewModelScope.launch {
            runCatchingApi { accountingConfigurationApiClient.getDocumentStorageConfig(shopId) }
                .onSuccess { config ->
                    state =
                        state.copy(
                            path = config.path,
                            maxUploadSizeInput = config.maxUploadSizeMb.toString(),
                            defaultDocumentTypes = config.defaultDocumentTypes,
                            additionalDocumentTypes = config.additionalDocumentTypes,
                            savedPath = config.path,
                            savedMaxUploadSizeInput = config.maxUploadSizeMb.toString(),
                            savedAdditionalDocumentTypes = config.additionalDocumentTypes,
                            isLoading = false,
                        )
                }.onError(retry = ::refresh) { uiError ->
                    log.e { "load document storage config failed shopId=$shopId" }
                    failedAction = ::refresh
                    state = state.copy(isLoading = false, error = uiError)
                }
        }
    }

    fun updatePath(path: String) {
        state = state.copy(path = path)
    }

    fun updateMaxUploadSize(value: String) {
        state = state.copy(maxUploadSizeInput = value)
    }

    fun updateNewDocumentType(value: String) {
        state = state.copy(newDocumentType = value)
    }

    fun addDocumentType() {
        if (!state.canAddDocumentType) return
        state =
            state.copy(
                additionalDocumentTypes = state.additionalDocumentTypes + state.newDocumentType.trim(),
                newDocumentType = "",
            )
    }

    fun removeDocumentType(typeName: String) {
        state = state.copy(additionalDocumentTypes = state.additionalDocumentTypes - typeName)
    }

    fun save() {
        val maxUploadSizeMb = state.maxUploadSizeMb
        if (maxUploadSizeMb == null) {
            // Fail fast, and say where: the field already carries its message; bumping the attempt
            // makes the section scroll to and focus it. Nothing is sent.
            log.w { "refusing to save an invalid upload size" }
            state = state.copy(validationAttempt = state.validationAttempt + 1)
            return
        }
        log.i { "saving document storage config shopId=$shopId" }
        state = state.copy(isSaving = true, error = null)
        val request =
            ShopConfigurationDto.UpdateDocumentStorageConfigRequestDto(
                path = state.path.trim(),
                maxUploadSizeMb = maxUploadSizeMb,
                additionalDocumentTypes = state.additionalDocumentTypes,
            )
        viewModelScope.launch {
            runCatchingApi { accountingConfigurationApiClient.updateDocumentStorageConfig(shopId, request) }
                .onSuccess { config ->
                    state =
                        state.copy(
                            path = config.path,
                            maxUploadSizeInput = config.maxUploadSizeMb.toString(),
                            defaultDocumentTypes = config.defaultDocumentTypes,
                            additionalDocumentTypes = config.additionalDocumentTypes,
                            savedPath = config.path,
                            savedMaxUploadSizeInput = config.maxUploadSizeMb.toString(),
                            savedAdditionalDocumentTypes = config.additionalDocumentTypes,
                            isSaving = false,
                            successMessage = AdminStringKeys.SettingsDocStorageSaved,
                        )
                }.onError { uiError ->
                    log.e { "save document storage config failed shopId=$shopId" }
                    failedAction = ::save
                    state = state.copy(isSaving = false, error = uiError)
                }
        }
    }

    /**
     * Opens the folder browser at the configured storage path so the operator sees where the
     * documents live now; a path that no longer lists (missing / unreadable: the server answers
     * 404) falls back to the server's default directory instead of showing an error.
     */
    fun openBrowser() {
        state = state.copy(browser = DirectoryBrowserState(isLoading = true))
        browseTo(state.path.trim().ifBlank { null }, fallbackToDefault = true)
    }

    fun closeBrowser() {
        state = state.copy(browser = null)
    }

    /**
     * @param path the directory to list, or null for the server's home directory
     * @param fallbackToDefault when [path] cannot be listed, list the default directory instead of reporting the error
     */
    fun browseTo(
        path: String?,
        fallbackToDefault: Boolean = false,
    ) {
        state = state.copy(browser = (state.browser ?: DirectoryBrowserState()).copy(isLoading = true))
        viewModelScope.launch {
            runCatchingApi { shopConfigurationApiClient.listDirectories(path) }
                .onSuccess { listing ->
                    state =
                        state.copy(
                            browser =
                                DirectoryBrowserState(
                                    currentPath = listing.currentPath,
                                    parentPath = listing.parentPath,
                                    // A storage root can only be a folder, so files are dropped here.
                                    entries = listing.entries.filter { it.isDirectory },
                                    canWrite = listing.canWrite,
                                    isLoading = false,
                                ),
                        )
                }.onError { uiError ->
                    if (fallbackToDefault && path != null) {
                        log.w { "configured storage path not listable, falling back to default path=$path" }
                        browseTo(null)
                        return@onError
                    }
                    log.e { "list directories failed path=$path" }
                    failedAction = { browseTo(path) }
                    state =
                        state.copy(
                            browser = state.browser?.copy(isLoading = false),
                            error = uiError,
                        )
                }
        }
    }

    fun navigateToParent() {
        val parentPath = state.browser?.parentPath ?: return
        browseTo(parentPath)
    }

    fun selectDirectory(path: String) {
        state = state.copy(path = path, browser = null)
    }

    fun clearError() {
        state = state.copy(error = null)
    }

    fun clearSuccessMessage() {
        state = state.copy(successMessage = null)
    }
}
