package org.example.project.features.terminal.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.example.project.BuildKonfig
import org.example.project.core.data.database.LocalDatabaseManager
import org.example.project.core.data.remote.TerminalApiClient
import org.example.project.core.device.DeviceInfoProvider
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.domain.enums.terminal.ClientKind
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.network.isPinningSupported
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.TerminalPairingKeys
import org.example.project.core.utils.platformIoDispatcher
import org.example.project.features.terminal.data.PinFailure
import org.example.project.features.terminal.data.RootCaPinner
import org.example.project.features.terminal.presentation.state.DevicePairingUiState

/**
 * ViewModel for the new-device pairing screen (Terminal Phase RB1).
 *
 * Calls the public pairing endpoint via the shared [TerminalApiClient.pairDevice], persists
 * the returned device token + terminal identity on [ioDispatcher] (sealing the token can block on
 * the Keystore or keychain), and invokes an `onPaired` callback. Errors surface as a typed [UiError]
 * in [DevicePairingUiState.error]; the two known pairing failures and a pairing this device could not
 * store get friendly localized messages.
 */
class DevicePairingViewModel(
    private val terminalApiClient: TerminalApiClient,
    private val localDb: LocalDatabaseManager,
    private val rootCaPinner: RootCaPinner = RootCaPinner(localDb),
    private val ioDispatcher: CoroutineDispatcher = platformIoDispatcher(),
) : ViewModel() {
    private val logger = Logger.withTag("DevicePairingViewModel")

    private val _state = MutableStateFlow(DevicePairingUiState())
    val state: StateFlow<DevicePairingUiState> = _state.asStateFlow()

    /** Keeps only digits, capped at 6 (server pairing code length). */
    fun updateCode(code: String) {
        _state.update { it.copy(code = code.filter(Char::isDigit).take(6), error = null) }
    }

    /** The operator's typed certificate check code; shape is validated when pairing starts. */
    fun updateRootCode(rootCode: String) {
        _state.update { it.copy(rootCode = rootCode.take(MAX_ROOT_CODE_INPUT), error = null) }
    }

    /**
     * Called once each time the pairing screen is (re)entered — after a revoke, a re-pair or a
     * session ended with `requiresRepairing`. The VM is retained across those visits, so without
     * this a code typed on a previous visit would reappear in the field.
     *
     * [prefillCode] is a code supplied by the pairing link (`?code=`); it fills the field but is
     * never submitted automatically.
     */
    fun onScreenEntered(prefillCode: String? = null) {
        _state.update { it.copy(code = prefillCode.orEmpty(), rootCode = "", isPairing = false, error = null) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /** Called when the URL-settings dialog has been dismissed by the user. */
    fun onUrlDialogDismissed() {
        _state.update { it.copy(showUrlDialog = false) }
    }

    /** Sets a friendly QR-read error (called by the screen when a scan cannot be parsed). */
    fun onScanFailed() {
        _state.update {
            it.copy(
                isPairing = false,
                error =
                    UiError.Unknown(
                        titleKey = TerminalPairingKeys.title,
                        messageKey = TerminalPairingKeys.errorQrInvalid,
                    ),
            )
        }
    }

    /** The scanned QR names a server this build refuses to talk to — plain HTTP to a LAN host, or no address at all. */
    fun onServerUrlRejected(messageKey: StringKey) {
        _state.update {
            it.copy(
                isPairing = false,
                error = UiError.Unknown(titleKey = TerminalPairingKeys.title, messageKey = messageKey),
            )
        }
    }

    /**
     * Pairs this device with [code]. On success stores the device token + terminal
     * identity and invokes [onPaired] with the terminal response.
     *
     * With a [rootCode] (scanned from the pairing QR or typed) the server's root certificate is
     * verified against it and pinned BEFORE the pairing code is sent, so the code only ever travels
     * over a connection this device has authenticated. Blank means the device already trusts the
     * server's certificate, as before.
     */
    fun pair(
        code: String,
        rootCode: String? = null,
        onPaired: (terminal: TerminalDto.TerminalResponseDto) -> Unit,
    ) {
        val trimmed = code.trim()
        if (trimmed.isBlank()) {
            _state.update {
                it.copy(
                    error =
                        UiError.Unknown(
                            titleKey = TerminalPairingKeys.title,
                            messageKey = TerminalPairingKeys.errorEmptyCode,
                        ),
                )
            }
            return
        }

        val rootCodeToPin = rootCode?.trim()?.takeIf { it.isNotEmpty() && isPinningSupported }
        _state.update { it.copy(isPairing = true, error = null) }
        viewModelScope.launch {
            if (rootCodeToPin != null) {
                val failure = rootCaPinner.pin(localDb.getApiBaseUrl(), rootCodeToPin)
                if (failure != null) {
                    logger.w { "Certificate pin refused: $failure" }
                    _state.update { it.copy(isPairing = false, error = pinFailureError(failure)) }
                    return@launch
                }
            }
            val request = buildPairRequest(trimmed, DeviceInfoProvider.getDeviceId())
            runCatchingApi { terminalApiClient.pairDevice(request) }
                .onSuccess { resp ->
                    storePairing(resp)
                        .onSuccess {
                            logger.i { "Device paired to ${resp.terminal.terminalId}" }
                            // The code is single-use and now consumed: never leave it in the field.
                            _state.update { it.copy(code = "", isPairing = false) }
                            onPaired(resp.terminal)
                        }.onFailure { e ->
                            _state.update { it.copy(code = "", isPairing = false, error = storageFailedError(e)) }
                        }
                }.onError { uiError ->
                    logger.w { "Pairing failed: $uiError" }
                    _state.update { it.copy(isPairing = false, error = mapPairingError(uiError)) }
                }
        }
    }

    /**
     * Stores the device token and terminal identity on [ioDispatcher].
     *
     * Sealing the token can fail — Keystore or keychain unavailable — and the store then writes nothing, so an earlier
     * pairing is left exactly as it was. That matters: the pairing screen shows while one is stored precisely when its
     * token cannot be opened right now, and clearing the registration would destroy the pairing the store kept. Only
     * once the new token has landed does a failure roll the pairing back, so the till is left unpaired, not half-paired.
     */
    private suspend fun storePairing(resp: TerminalDto.PairDeviceResponseDto): Result<Unit> =
        withContext(ioDispatcher) {
            var tokenWritten = false
            try {
                localDb.setDeviceToken(resp.deviceToken)
                tokenWritten = true
                localDb.setTerminalRegistered(resp.terminal.terminalId, resp.terminal.id)
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (tokenWritten) {
                    logger.e(e) { "Could not store the terminal registration — rolling the pairing back" }
                    localDb.clearTerminalRegistration()
                } else {
                    logger.e(e) { "Could not store the device token — leaving any earlier pairing as it was" }
                }
                Result.failure(e)
            }
        }

    /**
     * The server has already used up the pairing code, so retrying it would only answer "code invalid":
     * the message asks for a new code instead.
     */
    private fun storageFailedError(cause: Throwable): UiError =
        UiError.Unknown(
            titleKey = TerminalPairingKeys.title,
            messageKey = TerminalPairingKeys.errorStorageFailed,
            cause = cause,
        )

    private fun pinFailureError(failure: PinFailure): UiError =
        UiError.Unknown(
            titleKey = TerminalPairingKeys.title,
            messageKey =
                when (failure) {
                    PinFailure.CODE_INVALID -> TerminalPairingKeys.errorRootCodeInvalid
                    PinFailure.FETCH_FAILED -> TerminalPairingKeys.errorRootFetchFailed
                    PinFailure.MISMATCH -> TerminalPairingKeys.errorRootMismatch
                },
        )

    /** Overrides the known pairing failures with friendly localized messages; passes others through. */
    private fun mapPairingError(uiError: UiError): UiError =
        when ((uiError as? UiError.FromServer)?.code) {
            PosErrorCode.PAIRING_CODE_INVALID ->
                UiError.Unknown(
                    titleKey = TerminalPairingKeys.title,
                    messageKey = TerminalPairingKeys.errorCodeInvalid,
                )

            PosErrorCode.PAIRING_CODE_EXPIRED ->
                UiError.Unknown(
                    titleKey = TerminalPairingKeys.title,
                    messageKey = TerminalPairingKeys.errorCodeExpired,
                )

            // The code asked for TSE registration and the TSE refused: nothing was bound, and only
            // the back office (TSE admin) can fix the TSE or issue a new code.
            PosErrorCode.PAIRING_TSE_REGISTRATION_FAILED ->
                UiError.Unknown(
                    titleKey = TerminalPairingKeys.title,
                    messageKey = TerminalPairingKeys.errorTseRegistrationFailed,
                )

            // The shop has not enabled browser stations for this role: nothing was bound, and only a
            // manager can change that.
            PosErrorCode.BROWSER_STATION_DISABLED ->
                UiError.Unknown(
                    titleKey = TerminalPairingKeys.title,
                    messageKey = TerminalPairingKeys.browserStationDisabled,
                )

            else -> uiError
        }

    companion object {
        /** A pasted code with separators is longer than its 26 characters; anything past this is not a code. */
        private const val MAX_ROOT_CODE_INPUT = 48

        /** The browser till's `pos-preview-` device ids pair as BROWSER; everything else as NATIVE. */
        internal fun buildPairRequest(
            code: String,
            deviceId: String,
        ): TerminalDto.PairDeviceRequestDto =
            TerminalDto.PairDeviceRequestDto(
                code = code,
                deviceId = deviceId,
                model = DeviceInfoProvider.getDeviceModel(),
                brand = DeviceInfoProvider.getPlatformBrand(),
                swVersion = BuildKonfig.APP_VERSION,
                clientKind = ClientKind.effective(null, deviceId).name,
            )
    }
}
