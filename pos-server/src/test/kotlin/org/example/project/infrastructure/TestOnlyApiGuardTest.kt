package org.example.project.infrastructure

import org.example.project.testutils.SourceRoots
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `@TestOnlyApi` marks the members that were widened from `internal` to public only so a test in
 * another module could reach them (B2-1b hand-over "Visibility"). The annotation is a compile-time
 * wall (`RequiresOptIn` at ERROR level); this guard keeps the wall from being walked around:
 *  - production code may opt in only inside the file that declares the member (its own call site);
 *  - a build file may opt in only for a test compilation, never for main;
 *  - every hand-over member actually carries the annotation, so a rename or a lost annotation fails here.
 */
@DisplayName("TestOnlyApi stays a test-only wall")
class TestOnlyApiGuardTest {
    private companion object {
        /** Kotlin files under the pos-server and platform-server main roots on 2026-10-27, as in the other source guards. */
        const val MINIMUM_MAIN_FILES = 740

        /** Hand-over members: declaring file (below the `kotlin` directory) and member name. */
        val HAND_OVER_MEMBERS =
            listOf(
                "org/example/project/core/websocket/NotificationHub.kt" to "resetForTest",
                "org/example/project/core/websocket/PendingBillingRequestStore.kt" to "reset",
                "org/example/project/features/scheduler/data/service/SchedulerEngine.kt" to "processDueJobs",
                "org/example/project/features/scheduler/data/service/SchedulerEngine.kt" to "recoverOrphans",
                "org/example/project/features/terminal/data/service/PairAttemptLimiter.kt" to "trackedKeyCount",
                "org/example/project/features/terminal/data/service/TlsRootCa.kt" to "resetForTests",
                "org/example/project/features/terminal/data/service/TlsRootCa.kt" to "fileProvider",
                "org/example/project/features/update/data/repository/UpdateSettingsRepositoryImpl.kt" to "rawCiphertextForTest",
                "org/example/project/features/issuereport/data/repository/IssueReportSettingsRepositoryImpl.kt" to "rawCiphertextForTest",
                "org/example/project/features/kassenbuch/data/service/DatevXmlExportServiceImpl.kt" to "mapHeaderToEntries",
                "org/example/project/features/partner/data/service/DatevStammdatenExportServiceImpl.kt" to "PartyRoles",
                "org/example/project/features/partner/data/service/DatevStammdatenExportServiceImpl.kt" to "Resolution",
                "org/example/project/features/partner/data/service/DatevStammdatenExportServiceImpl.kt" to "resolve",
                "org/example/project/features/statistics/domain/routes/StatisticsRoutes.kt" to "clampToTradingDay",
                "org/example/project/features/customexport/data/service/CustomExportServiceImpl.kt" to "requireDateFilterApplies",
                "org/example/project/features/customexport/data/service/CustomExportServiceImpl.kt" to "fileNameFor",
                "org/example/project/features/customexport/data/service/CustomExportServiceImpl.kt" to "renderCell",
                "org/example/project/features/customexport/data/service/CustomExportServiceImpl.kt" to "joinSourceFor",
                "org/example/project/infrastructure/plugins/ApiDocsPolicy.kt" to "readServerOverride",
                "org/example/project/features/bill/data/service/BillServiceImpl.kt" to "scaleDeductionsToCap",
                "org/example/project/features/dsfinvk/data/service/DsfinvkExportServiceImpl.kt" to "rabattLabel",
                "org/example/project/features/dsfinvk/data/service/DsfinvkExportServiceImpl.kt" to "generateLineItems",
                "org/example/project/features/dsfinvk/data/service/DsfinvkExportServiceImpl.kt" to "generateAvLineVats",
                "org/example/project/features/dsfinvk/data/service/DsfinvkExportServiceImpl.kt" to "generateDataPayments",
                "org/example/project/features/dsfinvk/data/service/DsfinvkExportServiceImpl.kt" to "generateAvPayments",
                "org/example/project/features/dsfinvk/data/service/DsfinvkExportServiceImpl.kt" to "generateBusinessCases",
                "org/example/project/features/dsfinvk/data/service/DsfinvkExportServiceImpl.kt" to "generatePaymentSummaries",
                "org/example/project/features/dsfinvk/data/service/DsfinvkExportServiceImpl.kt" to "filterExportableKassenbuchEntries",
                "org/example/project/features/dsfinvk/data/service/DsfinvkExportServiceImpl.kt" to "generateKassenbuchLineItems",
                "org/example/project/features/dsfinvk/data/service/DsfinvkExportServiceImpl.kt" to "aggregateKassenbuchBusinessCases",
                "org/example/project/features/dsfinvk/data/service/DsfinvkExportServiceImpl.kt" to "appendKassenbuchBonkopfUst",
                "org/example/project/features/dsfinvk/data/service/DsfinvkExportServiceImpl.kt" to "deriveTseSerialHashHex",
                "org/example/project/features/zBon/domain/FiscalShopIdentityGuard.kt" to "changesFiscalIdentity",
                "org/example/project/features/zBon/domain/ClosingSigner.kt" to "unfitReason",
                "org/example/project/features/tse/data/service/TseConfigurationServiceImpl.kt" to "mapInitResult",
                "org/example/project/features/tse/data/service/TseConfigurationServiceImpl.kt" to "legacyApplyStatus",
                "org/example/project/features/tse/data/service/TsePendingSweeper.kt" to "sweep",
            )

        val SKIPPED_DIRS = setOf("build", "bin", ".gradle", ".git", ".claude", ".idea", "node_modules", ".kotlin")
    }

    private fun mainFiles(): List<File> =
        SourceRoots.kotlinFiles(SourceRoots.allModuleMainRoots()).also {
            SourceRoots.requireScanned("TestOnlyApiGuardTest", it.size, MINIMUM_MAIN_FILES)
        }

    private fun gradleFiles(): List<File> =
        SourceRoots.repoRoot
            .walkTopDown()
            .onEnter { it.name !in SKIPPED_DIRS }
            .filter { it.isFile && it.name.endsWith(".gradle.kts") }
            .toList()
            .also { assertTrue(it.size >= 10, "found only ${it.size} *.gradle.kts files — the walk is broken") }

    @Test
    fun `production code opts in to TestOnlyApi only in the file that declares the member`() {
        val offenders =
            mainFiles()
                .filter { it.name != "TestOnlyApi.kt" }
                .filter { file ->
                    val text = file.readText()
                    "OptIn(TestOnlyApi::class)" in text && !Regex("""@TestOnlyApi\b""").containsMatchIn(text)
                }.map { SourceRoots.pathInRoot(it) }
        assertEquals(emptyList(), offenders, "production files opting in to TestOnlyApi without declaring a @TestOnlyApi member")
    }

    @Test
    fun `build files opt in to TestOnlyApi only for test compilations`() {
        val offenders =
            gradleFiles().flatMap { file ->
                file
                    .readLines()
                    .withIndex()
                    .filter { (_, line) -> "TestOnlyApi" in line && "Test" !in line.replace("TestOnlyApi", "") }
                    .map { (index, line) -> "${file.relativeTo(SourceRoots.repoRoot)}:${index + 1}: ${line.trim()}" }
            }
        assertEquals(emptyList(), offenders, "a build file opts in to TestOnlyApi outside a test compilation")
    }

    @Test
    fun `every hand-over member carries TestOnlyApi`() {
        val roots = SourceRoots.serverMainRoots()
        val missing =
            HAND_OVER_MEMBERS.filterNot { (path, name) ->
                val text = SourceRoots.fileUnder(roots, path).readText()
                Regex("""@TestOnlyApi\s+(?:@\w+(?:\([^)]*\))?\s+)*(?:\w+\s+)*(?:fun|val|var|class)\s+(?:<[^>]*>\s*)?$name\b""")
                    .containsMatchIn(text)
            }
        assertEquals(emptyList(), missing, "hand-over members without @TestOnlyApi")
    }
}
