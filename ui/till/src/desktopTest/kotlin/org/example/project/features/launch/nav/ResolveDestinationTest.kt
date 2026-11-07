package org.example.project.features.launch.nav

import org.example.project.core.auth.SessionStatus
import org.example.project.core.domain.enums.terminal.TerminalRole
import org.example.project.features.launch.presentation.nav.AppDestination
import org.example.project.features.launch.presentation.nav.DeviceResolution
import org.example.project.features.launch.presentation.nav.LaunchInputs
import org.example.project.features.launch.presentation.nav.StationBlock
import org.example.project.features.launch.presentation.nav.resolveDestination
import kotlin.test.Test
import kotlin.test.assertEquals

class ResolveDestinationTest {
    private fun inputs(
        session: SessionStatus = SessionStatus.UNAUTHENTICATED,
        requiresSetup: Boolean? = false,
        device: DeviceResolution = DeviceResolution.Checking,
        offline: Boolean = false,
        stationBlocked: StationBlock? = null,
        stationSession: Boolean = false,
        deviceTokenStored: Boolean = false,
    ) = LaunchInputs(
        session,
        requiresSetup,
        device,
        offline,
        stationBlocked = stationBlocked,
        stationSession = stationSession,
        deviceTokenStored = deviceTokenStored,
    )

    @Test fun offline_beats_everything() =
        assertEquals(
            AppDestination.Offline(canChangeServerAddress = false),
            resolveDestination(
                inputs(
                    requiresSetup = true,
                    device = DeviceResolution.Paired("T", TerminalRole.CASHIER),
                    offline = true,
                    deviceTokenStored = true,
                ),
            ),
        )

    /** A fresh install on the platform default address must be able to reach the real server. */
    @Test fun offline_without_a_stored_device_token_offers_the_server_address_change() =
        assertEquals(
            AppDestination.Offline(canChangeServerAddress = true),
            resolveDestination(inputs(requiresSetup = null, offline = true)),
        )

    /** The offline check runs before the device check, so the stored token is all there is to go on. */
    @Test fun offline_with_a_stored_device_token_never_offers_the_server_address_change() =
        assertEquals(
            AppDestination.Offline(canChangeServerAddress = false),
            resolveDestination(inputs(requiresSetup = null, offline = true, deviceTokenStored = true)),
        )

    @Test fun setup_unknown_is_resolving() = assertEquals(AppDestination.Resolving, resolveDestination(inputs(requiresSetup = null)))

    @Test fun setup_required() = assertEquals(AppDestination.Setup(canChangeServerAddress = true), resolveDestination(inputs(requiresSetup = true)))

    /** A paired till whose server was reset lands on the setup screen; its token still stays put. */
    @Test fun setup_required_with_a_stored_device_token_never_offers_the_server_address_change() =
        assertEquals(
            AppDestination.Setup(canChangeServerAddress = false),
            resolveDestination(inputs(requiresSetup = true, deviceTokenStored = true)),
        )

    @Test fun device_checking_is_resolving() = assertEquals(AppDestination.Resolving, resolveDestination(inputs(device = DeviceResolution.Checking)))

    @Test fun no_token_is_pairing() = assertEquals(AppDestination.Pairing, resolveDestination(inputs(device = DeviceResolution.NoToken)))

    @Test fun rejected_is_pairing() = assertEquals(AppDestination.Pairing, resolveDestination(inputs(device = DeviceResolution.Rejected)))

    @Test fun cashier_unauthenticated_is_login() =
        assertEquals(
            AppDestination.Login,
            resolveDestination(
                inputs(session = SessionStatus.UNAUTHENTICATED, device = DeviceResolution.Paired("T", TerminalRole.CASHIER)),
            ),
        )

    @Test fun cashier_authenticated_is_main() =
        assertEquals(
            AppDestination.Main,
            resolveDestination(inputs(session = SessionStatus.AUTHENTICATED, device = DeviceResolution.Paired("T", TerminalRole.CASHIER))),
        )

    /**
     * The station marker is an ORDER_STATION concern only. A till's session is a person's by
     * definition, so gating Main on the marker here would lock every cashier out of the app.
     */
    @Test fun cashier_authenticated_is_main_whatever_the_station_marker_says() {
        for (stationSession in listOf(false, true)) {
            assertEquals(
                AppDestination.Main,
                resolveDestination(
                    inputs(
                        session = SessionStatus.AUTHENTICATED,
                        device = DeviceResolution.Paired("T", TerminalRole.CASHIER),
                        stationSession = stationSession,
                    ),
                ),
                "stationSession=$stationSession",
            )
        }
    }

    @Test fun authenticated_but_no_token_is_pairing_not_main() =
        assertEquals(
            AppDestination.Pairing,
            resolveDestination(inputs(session = SessionStatus.AUTHENTICATED, device = DeviceResolution.NoToken)),
        )

    @Test fun order_station_unauthenticated_is_station_session() =
        assertEquals(
            AppDestination.StationSession,
            resolveDestination(inputs(device = DeviceResolution.Paired("T7", TerminalRole.ORDER_STATION))),
        )

    @Test fun order_station_authenticated_by_its_own_exchange_is_main() =
        assertEquals(
            AppDestination.Main,
            resolveDestination(
                inputs(
                    session = SessionStatus.AUTHENTICATED,
                    device = DeviceResolution.Paired("T7", TerminalRole.ORDER_STATION),
                    stationSession = true,
                ),
            ),
        )

    /**
     * The regression this flag exists for. A device someone had signed into as a person while it
     * was still a CASHIER keeps that session when its terminal role is switched to ORDER_STATION.
     * Reading AUTHENTICATED alone resolved it to Main, which is the only place the station-session
     * exchange is NOT triggered from — so the device never obtained a station JWT and went on
     * presenting the human's token to every request, indefinitely.
     */
    @Test fun order_station_authenticated_as_a_person_is_station_session_not_main() =
        assertEquals(
            AppDestination.StationSession,
            resolveDestination(
                inputs(
                    session = SessionStatus.AUTHENTICATED,
                    device = DeviceResolution.Paired("T7", TerminalRole.ORDER_STATION),
                    stationSession = false,
                ),
            ),
        )

    /** A server-side fault still wins over re-running an exchange that cannot succeed yet. */
    @Test fun order_station_blocked_is_station_blocked() =
        assertEquals(
            AppDestination.StationBlocked(StationBlock.TSE_CLIENT_NOT_REGISTERED),
            resolveDestination(
                inputs(
                    device = DeviceResolution.Paired("T7", TerminalRole.ORDER_STATION),
                    stationBlocked = StationBlock.TSE_CLIENT_NOT_REGISTERED,
                ),
            ),
        )

    /** A person's session does not start the station, so the block it is holding still shows. */
    @Test fun order_station_blocked_beats_a_person_session() =
        assertEquals(
            AppDestination.StationBlocked(StationBlock.SERVER_INSTALLATION_INCOMPLETE),
            resolveDestination(
                inputs(
                    session = SessionStatus.AUTHENTICATED,
                    device = DeviceResolution.Paired("T7", TerminalRole.ORDER_STATION),
                    stationBlocked = StationBlock.SERVER_INSTALLATION_INCOMPLETE,
                ),
            ),
        )

    /** Once the exchange has succeeded, a block left over from the attempt before it is spent. */
    @Test fun a_started_station_beats_a_stale_block() =
        assertEquals(
            AppDestination.Main,
            resolveDestination(
                inputs(
                    session = SessionStatus.AUTHENTICATED,
                    device = DeviceResolution.Paired("T7", TerminalRole.ORDER_STATION),
                    stationBlocked = StationBlock.TSE_CLIENT_NOT_REGISTERED,
                    stationSession = true,
                ),
            ),
        )

    @Test fun kitchen_is_placeholder() =
        assertEquals(
            AppDestination.RolePlaceholder("T7", "KITCHEN"),
            resolveDestination(inputs(device = DeviceResolution.Paired("T7", TerminalRole.KITCHEN))),
        )

    @Test fun insecure_server_url_beats_offline_and_setup() =
        assertEquals(
            AppDestination.InsecureServerUrl,
            resolveDestination(inputs(requiresSetup = true, offline = true).copy(insecureServerUrl = true)),
        )

    @Test fun a_secure_url_does_not_change_resolution() =
        assertEquals(AppDestination.Offline(canChangeServerAddress = true), resolveDestination(inputs(offline = true).copy(insecureServerUrl = false)))

    @Test fun an_untrusted_certificate_on_an_unpaired_device_goes_to_pairing() =
        assertEquals(
            AppDestination.Pairing,
            resolveDestination(inputs(requiresSetup = null, offline = true).copy(certificateUntrusted = true)),
        )

    @Test fun an_untrusted_certificate_on_a_paired_device_is_offline_with_the_check_code_offered() =
        assertEquals(
            AppDestination.Offline(canChangeServerAddress = false, certificateUntrusted = true),
            resolveDestination(inputs(requiresSetup = null, offline = true, deviceTokenStored = true).copy(certificateUntrusted = true)),
        )

    @Test fun an_ordinary_network_failure_is_still_offline_even_without_a_token() =
        assertEquals(
            AppDestination.Offline(canChangeServerAddress = true),
            resolveDestination(inputs(requiresSetup = null, offline = true).copy(certificateUntrusted = false)),
        )
}
