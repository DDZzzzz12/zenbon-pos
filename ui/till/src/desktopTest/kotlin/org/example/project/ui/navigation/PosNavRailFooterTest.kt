package org.example.project.ui.navigation

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The nav footer's leave action on both shells (design §3.2, §3.5).
 *
 * An order station has no person signed in, so "Logout" is meaningless there — the footer offers
 * "re-pair this device" instead, which must go through the EXISTING cold-reset path
 * (`TerminalColdResetBus` → `TerminalLifecycleHandler.coldReset()`), the same teardown the Ktor
 * auth plugin fires on a terminal-auth failure. A hand-rolled teardown would clear the session and
 * leave the device token behind, stranding the terminal on a pairing screen it is still paired for.
 *
 * **Source-scanned, not composed.** The plan drafted this as a `runComposeUiTest` passing
 * `isStation` to `PosNavRail`; that parameter no longer exists — the containers read
 * [rememberIsStation], which resolves a `TokenStore` out of Koin — and composing either container
 * would additionally need `LocalSettingManager`, `LocalPermissionManager` and a started Koin
 * application, none of which composeApp's test source set stands up today. The same reasoning
 * `NoRoleGatesInScreensTest` and `NavContainersGateDestinationsTest` give for scanning text.
 */
class PosNavRailFooterTest {
    private val containers = listOf("PosNavRail.kt")

    private fun navSource(name: String): String {
        val file = java.io.File("src/commonMain/kotlin/org/example/project/ui/navigation").resolve(name)
        assertTrue(file.isFile, "expected ${file.path} to exist")
        return file.readText()
    }

    @Test
    fun `the nav rail swaps logout for re-pair on a station`() {
        containers.forEach { name ->
            val text = navSource(name)
            assertTrue(
                text.contains("rememberIsStation()"),
                "$name must ask rememberIsStation() — the one spelling of the station marker",
            )
            assertTrue(
                text.contains("LaunchKeys.repairDevice"),
                "$name must label the station's leave action with LaunchKeys.repairDevice",
            )
            assertTrue(
                text.contains("if (isStation) onRepair else onLogout"),
                "$name must route the station's leave action to onRepair, not onLogout",
            )
        }
    }

    /**
     * Every container the shell can render, not only the rail.
     *
     * The bottom bar was left out of the first version of this and of the change it tested, so
     * "re-pair" shipped on medium and expanded only — and a compact station had no way off the
     * device at all, since Logout lives inside Settings and Settings is withheld from a station.
     * Counting *containers* rather than naming the rail is what makes the omission visible.
     */
    @Test
    fun `the shell hands onRepair to every nav container it renders`() {
        val shell = navSource("PosNavShell.kt")
        val declarations = Regex("""Pos(NavRail|BottomBar)\(""").findAll(shell).count()
        val wirings = Regex("""onRepair = onRepair,""").findAll(shell).count()
        assertTrue(declarations > 0, "PosNavShell renders no nav container — has the shell been restructured?")
        assertTrue(
            wirings == declarations,
            "PosNavShell renders $declarations nav containers but wires onRepair to $wirings of them; " +
                "a container that falls back to the no-op default leaves a station with no way to " +
                "hand the device back to pairing",
        )
    }

    /**
     * The compact shell's leave action. It is not the rail's footer item — the bar has three slots
     * and a leave action is not a destination — so it hangs off the management menu instead, which
     * must therefore open even when a station has no management destinations to put in it.
     */
    @Test
    fun `the bottom bar offers re-pair on a station`() {
        val text = navSource("PosBottomBar.kt")
        assertTrue(
            text.contains("rememberIsStation()"),
            "PosBottomBar must ask rememberIsStation() — the one spelling of the station marker",
        )
        assertTrue(
            text.contains("LaunchKeys.repairDevice"),
            "PosBottomBar must label the station's leave action with LaunchKeys.repairDevice",
        )
        assertTrue(
            Regex("""menuItems\.isNotEmpty\(\)\s*\|\|""").containsMatchIn(text),
            "PosBottomBar must open its menu for the re-pair item even with no management " +
                "destinations, or a station's only exit is hidden behind an empty menu",
        )
    }

    /**
     * "Backoffice öffnen" — on BOTH shells, in every container the shell can render.
     *
     * Deliberately ungated, so it must not sit inside an `isStation` branch, behind a
     * `RequireCapability`, or among the manager-gated management destinations: the admin panel
     * enforces its own login, and an anonymous helper at a station reaches only its login page.
     * Withholding it would only teach staff to type the server address by hand.
     */
    @Test
    fun `every nav container offers the back office, ungated, on both shells`() {
        (containers + "PosBottomBar.kt").forEach { name ->
            val lines = navSource(name).lines()
            assertTrue(
                lines.any { "LaunchKeys.openBackoffice" in it.substringBefore("//") },
                "$name must label the back-office action with LaunchKeys.openBackoffice",
            )
            val gated =
                lines.filter { line ->
                    val code = line.substringBefore("//")
                    "openBackoffice" in code && ("isStation" in code || "RequireCapability" in code)
                }
            assertTrue(
                gated.isEmpty(),
                "$name gates the back-office action; the panel enforces its own login and the " +
                    "action is offered to a station and a person alike:\n" + gated.joinToString("\n"),
            )
        }
    }

    /** Same reasoning as `onRepair` above: a container falling back to the no-op default is invisible. */
    @Test
    fun `the shell hands onOpenBackoffice to every nav container it renders`() {
        val shell = navSource("PosNavShell.kt")
        val declarations = Regex("""Pos(NavRail|BottomBar)\(""").findAll(shell).count()
        val wirings = Regex("""onOpenBackoffice = onOpenBackoffice,""").findAll(shell).count()
        assertTrue(declarations > 0, "PosNavShell renders no nav container — has the shell been restructured?")
        assertTrue(
            wirings == declarations,
            "PosNavShell renders $declarations nav containers but wires onOpenBackoffice to $wirings of them",
        )
    }

    /** The URL is derived once, by the shared helper, from the base URL stored at pairing. */
    @Test
    fun `the back-office URL comes from the shared helper, not a hand-built string`() {
        val main =
            java.io
                .File("src/commonMain/kotlin/org/example/project/ui/screens/main/MainScreen.kt")
                .readText()
        assertTrue(
            main.contains("BackofficeUrl.from(localDb.getApiBaseUrl())"),
            "MainScreen must derive the admin URL with BackofficeUrl.from(), which is the one " +
                "place that normalises a hand-typed base URL",
        )
        assertTrue(
            main.contains("onOpenBackoffice = onOpenBackoffice,"),
            "MainScreen must hand the back-office action to PosNavShell",
        )
    }

    @Test
    fun `re-pair goes through the existing cold-reset bus`() {
        val main =
            java.io
                .File("src/commonMain/kotlin/org/example/project/ui/screens/main/MainScreen.kt")
                .readText()
        assertTrue(
            main.contains("koinInject<TerminalColdResetBus>()"),
            "MainScreen must resolve the shared TerminalColdResetBus",
        )
        assertTrue(
            main.contains("onRepair = { coldResetBus.request(TerminalResetKind.COLD_OPERATOR_REPAIR) }"),
            "MainScreen must wire onRepair to the cold-reset bus rather than hand-rolling a teardown",
        )
    }
}
