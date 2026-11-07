import java.security.MessageDigest
import java.util.zip.ZipInputStream

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlinSerialization)
    `java-library`
    application
}

group = "org.example.project"
version = project.property("app.version") as String

// Deployment metadata values (generateDeploymentMeta). The BuildConfig generation that also read them
// moved to :platform-server (Stage B2-1b), which this module sees through `api(projects.platformServer)`.
val appVersion = version.toString()
val appName = project.property("app.name") as String

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.time.ExperimentalTime")
    }
}

// Test hooks widened from `internal` carry @TestOnlyApi (RequiresOptIn, ERROR level). Only test
// compilations opt in; main never does. TestOnlyApiGuardTest fails any build-file line that names it
// without naming a test compilation.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") { compilerOptions.optIn.add("org.example.project.utils.TestOnlyApi") }

application {
    mainClass.set("org.example.project.ApplicationKt")
}

// Experimental: the Ktor compiler plugin derives OpenAPI metadata from the routing
// tree so /openapi-generated.json can be diffed against the hand-written YAML.
ktor {
    openApi {
        enabled = true
        codeInferenceEnabled = true
        onlyCommented = false
    }
}

// The Ktor OpenAPI compiler plugin applies to every compilation and currently ICEs on the
// test sources ("Parent of element ... is not initialized"). Its options cannot be overridden
// per-compilation, so drop the plugin itself from the test compiler classpath.
configurations.named("kotlinCompilerPluginClasspathTest") {
    exclude(group = "io.ktor", module = "ktor-compiler-plugin")
}

// Proprietary Swissbit SDK, owned by :pos-tse. Referenced here only for the test
// runtime classpath and for `:pos-server:run` — never for the published fat JAR.
val wormApiJar = project(":pos-tse").file("lib/WormAPI.jar")

// Resolves :pos-tse's virtualWorm test double for the runVirtual task below. Test-only —
// it never joins the main runtime classpath, so virtual classes cannot reach a distribution.
val virtualWorm: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

// Database binaries for local development only: on the `run`/`runVirtual` classpath, never on
// runtimeClasspath, so shadowJar cannot embed them in a release artifact.
val devDatabaseBinaries: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    // Area api modules (DTOs, error types, enums, API clients)
    implementation(projects.platformApi)
    implementation(projects.ecrApi)
    implementation(projects.accountingApi)

    // The :platform-money kernel, for the few shared rules whose API is in Decimal (RebookTender, the
    // Zahlartwechsel tender rule the till and the server share). Server arithmetic stays BigDecimal.
    implementation(projects.platformMoney)

    // The platform server tier (Stage B2-1b): `api` so this host and its consumers keep seeing the
    // platform routes/services once they have moved out, and the generated BuildConfig it now owns.
    api(projects.platformServer)

    // The ecr server tier (Stage B2-2): `api` so this host keeps seeing the ecr routes/services once they have moved out.
    api(projects.ecrServer)
    // The fiscal server tier (Stage B2-3): `api` so this host keeps seeing the fiscal routes/services once they have moved out.
    api(projects.fiscalServer)
    // The accounting server tier (Stage B2-4): `api` so this host keeps seeing the accounting routes/services once they have moved out.
    api(projects.accountingServer)
    // The backoffice server tier (Stage B2-5): `api` so this host keeps seeing the statistics, custom-export and backoffice configuration routes/services once they have moved out.
    api(projects.backofficeServer)

    // Server-common utilities (dbQuery, formatters, error handling)
    api(projects.platformServerCommon)

    // Feature modules
    implementation(projects.ecrCustomer)
    implementation(projects.ecrReservation)
    implementation(projects.platformAudit)
    implementation(projects.backofficeTimetracking)
    // Using api() so that IppPrinterService is visible to composeApp (PrinterDiscoveryApiClientAccessor)
    api(projects.ecrPrinter)
    implementation(projects.accountingDocument)
    implementation(projects.ecrVoucher)
    implementation(projects.fiscalExport)
    implementation(projects.fiscalProcessData)
    implementation(projects.ecrReceipt)

    // XML Serialization (xmlutil — kotlinx.serialization XML format for DATEV/DSFinV-K)
    implementation(libs.xmlutil.serialization.jvm)

    // QR Code Generation (DSFinV-K compliance)
    implementation(libs.zxing.core)
    implementation(libs.zxing.javase)

    // TSE module for local hardware access (LOCAL connection mode)
    implementation(projects.posTse)

    // Ktor Server Core
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    implementation(libs.ktor.serverContentNegotiation)
    implementation(libs.ktor.serverAuth)
    implementation(libs.ktor.serverAuthJwt)
    implementation(libs.ktor.serverCallLogging)
    implementation(libs.ktor.serverStatusPages)
    implementation(libs.ktor.serverCallId)
    implementation(libs.ktor.serverRequestValidation)
    implementation(libs.ktor.serverCors)
    implementation(libs.ktor.serverWebsockets)
    implementation(libs.ktor.serverSwagger)
    implementation(libs.ktor.serverOpenApi)
    implementation(libs.ktor.serverRoutingOpenApi)
    implementation(libs.ktor.ktor.server.compression)
    implementation(libs.ktor.serverConditionalHeaders)
    implementation(libs.ktor.serverForwardedHeader)

    // Ktor Serialization
    implementation(libs.ktor.serializationKotlinxJson)

    // Database and ORM
    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.kotlinDatetime)
    implementation(libs.exposed.json)
    implementation(libs.hikaricp)
    implementation(libs.postgresql)

    // Database Migrations
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)

    // Embedded PostgreSQL (self-contained deployment without external PG installation).
    //
    // Only the two DEPLOYMENT platforms ship. The alpine artifact is excluded rather than merely
    // unused: it arrives transitively at 14.22.0 and is NOT lifted to 16.6.0 by the lines below,
    // so it would put PostgreSQL 14 binaries in a jar whose schema was created on 16.
    //
    // darwin-amd64 is development-only and lives in devDatabaseBinaries (below), off the runtime
    // classpath and therefore out of the shadowJar. It must stay resolvable for `run`: macOS on
    // Apple Silicon runs the x86_64 build under Rosetta — see EmbeddedPostgresSupport's KDoc. It is
    // excluded here too, not merely un-declared: dropping its runtimeOnly line does not remove it,
    // it only stops lifting the transitive 14.22.0 copy to 16.6.0. The exclude scopes this
    // dependency's subgraph only, so the explicit devDatabaseBinaries and testRuntimeOnly
    // declarations still resolve it at 16.6.0.
    implementation(libs.embedded.postgres) {
        exclude(group = "io.zonky.test.postgres", module = "embedded-postgres-binaries-linux-amd64-alpine")
        exclude(group = "io.zonky.test.postgres", module = "embedded-postgres-binaries-darwin-amd64")
        exclude(group = "io.zonky.test.postgres", module = "embedded-postgres-binaries-windows-amd64")
    }
    runtimeOnly(libs.embedded.postgres.binaries.linux.amd64)
    devDatabaseBinaries(libs.embedded.postgres.binaries.darwin.amd64)

    // Dependency Injection (koin.core provided transitively by shared module)
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)

    // Security and Authentication
    implementation(libs.jwt)
    implementation(libs.bcrypt)

    // PDF processing (PDFBox for text extraction from digital PDFs)
    implementation(libs.pdfbox)

    // Logging
    implementation(libs.logback)
    implementation(libs.logstash.logback.encoder)

    // Configuration - Typesafe Config with YAML support
    implementation(libs.config)
    implementation(libs.ktor.serverConfigYaml)

    // Testing
    testImplementation(libs.ktor.serverTestHost)
    // MockEngine backs GithubReleaseClientTest / GithubAssetDownloadTest — the update check talks
    // to api.github.com, so its only honest test double is a scripted HTTP engine.
    testImplementation(libs.ktor.clientMock)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.h2)

    // commons-compress writes the zip64 fixture JarStructureTest needs: no real artifact is zip64
    // yet, and java.util.zip offers no way to force the zip64 records on a small archive.
    // Already on testRuntimeClasspath transitively; declared explicitly so the test compiles.
    testImplementation(libs.commons.compress)

    // Embedded PostgreSQL (zonky) for the @Tag("postgres") migration-chain tests and the
    // @Tag("realdata") local integration check.
    // linux-amd64 covers CI runners; darwin-amd64 runs under Rosetta on Apple Silicon (there is NO
    // arm64 alias in the catalog). Both are declared so CI and local Mac both resolve a binary.
    // These are gated behind the "postgres"/"realdata" JUnit tags and the dedicated postgresTest/
    // realdataTest Gradle tasks — the default `test` task stays H2-only, so a missing platform
    // binary can never turn the standard suite red.
    //
    // Deliberately NOT the main wiring: this declaration carries no excludes, so testRuntimeClasspath
    // still pulls the transitive linux-amd64-alpine artifact at 14.22.0. That is fine and left alone
    // — a test classpath is never shipped, so its size and its PG version skew reach no operator.
    // verifyDatabaseBinaries therefore inspects runtimeClasspath only and says nothing about this.
    testImplementation(libs.embedded.postgres)
    testRuntimeOnly(libs.embedded.postgres.binaries.linux.amd64)
    testRuntimeOnly(libs.embedded.postgres.binaries.darwin.amd64)

    // :pos-tse declares the Swissbit SDK compileOnly so it never reaches this
    // module's shadowJar. Tests still load TSE classes (TestAppSetup, TseAdminRoutesTest,
    // TseConfigRoutesTest, ...), so put it back on the test runtime classpath only.
    testImplementation(files(wormApiJar))

    // :accounting-document consumes :accounting-einvoice with `implementation`, so the parser is not on this
    // module's compile classpath transitively. ServicesModule registers the parser implementation
    // in DI, which needs it at compile scope; ParsedInvoiceWriterTest and
    // EInvoiceUploadIntegrationTest inherit it from here.
    implementation(projects.accountingEinvoice)

    // Same story for :accounting-banking — :accounting-document consumes it with `implementation`, so the parser
    // is not on this module's compile classpath transitively. ServicesModule registers the parser
    // implementation in DI, which needs it at compile scope; ParsedStatementWriterTest and
    // BankStatementUploadIntegrationTest inherit it from here.
    implementation(projects.accountingBanking)

    virtualWorm(project(mapOf("path" to ":pos-tse", "configuration" to "virtualWormElements")))
}

// Native library path for TSE WormAPI (LOCAL connection mode)
val nativeLibDir =
    when {
        else ->
            if (System.getProperty("os.arch").contains("64") || System.getProperty("os.arch").contains("amd64")) "linux64" else "linux32"
    }

val tseNativeLibPath = project(":pos-tse").file("src/main/resources/native/$nativeLibDir").absolutePath

application {
    applicationDefaultJvmArgs =
        listOf(
            "-Dio.ktor.development=${project.ext.has("development")}",
            "-Djava.library.path=$tseNativeLibPath",
            "-Djna.library.path=$tseNativeLibPath",
        )
}

// WormAPI is compileOnly in :pos-tse, so `./gradlew :pos-server:run` must add it
// to the runtime classpath explicitly. Packaged deployments resolve it from
// native/WormAPI.jar via the shadowJar Class-Path manifest entry instead.
tasks.named<JavaExec>("run") {
    classpath += files(wormApiJar)
    classpath += devDatabaseBinaries

    // Local dev convenience: load `pos-server/.env` into the run environment so
    // `./gradlew :pos-server:run` picks up local settings (an isolated dev database, dev-mode auth,
    // …) without exporting anything by hand. The file is git-ignored; `.env.example` is the template.
    // Lines are `KEY=VALUE`; blanks and `#` comments are skipped, surrounding quotes are stripped.
    // A real environment variable of the same name always wins, so CI and one-off overrides are
    // unaffected. `EMBEDDED_PG_DATA_DIR` is resolved to an absolute path under the module (so a
    // relative `.dev-data/postgresql` lands in the repo, never the real ~/POS Server database).
    val dotenv = project.file(".env")
    if (dotenv.exists()) {
        dotenv.readLines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val eq = line.indexOf('=')
            if (eq <= 0) return@forEach
            val key = line.substring(0, eq).trim()
            var value = line.substring(eq + 1).trim()
            if (value.length >= 2 &&
                ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))
            ) {
                value = value.substring(1, value.length - 1)
            }
            if (key == "EMBEDDED_PG_DATA_DIR" && value.isNotEmpty() && !file(value).isAbsolute) {
                value = project.file(value).absolutePath
            }
            if (System.getenv(key) == null) environment(key, value)
        }
        logger.lifecycle("pos-server: loaded ${dotenv.name} for run")
    }
}

// Runs the whole POS server against an in-memory virtual TSE — no hardware required.
// The virtualWorm classes shadow WormAPI.jar on the runtime classpath, exactly as
// :pos-tse's own test task does.
//
//   ./gradlew :pos-server:runVirtual                       (virtual TSE 2.0, the default)
//   ./gradlew :pos-server:runVirtual -Ptse.generation=v1   (virtual TSE 1.x)
//
// Deliberately NOT wired into shadowJar or packageForDeployment.
tasks.register<JavaExec>("runVirtual") {
    group = "application"
    description = "Runs pos-server against a virtual TSE (no hardware required)"

    mainClass.set("org.example.project.ApplicationKt")
    classpath = virtualWorm +
        sourceSets.main
            .get()
            .runtimeClasspath
            .filter { it.name != "WormAPI.jar" } + devDatabaseBinaries

    systemProperty("tse.virtual.device", "present")
    systemProperty("tse.virtual.generation", providers.gradleProperty("tse.generation").getOrElse("v2"))
    systemProperty("java.library.path", tseNativeLibPath)
}

// Root-level configuration files a dependency jar may carry that would override the project's own
// logging or server configuration if shaded into pos-server.jar. Excluded from shadowJar and
// asserted absent by verifyShadowJarLoggingConfig (F66).
val shadowJarForeignRootConfigs =
    listOf(
        "logback-test.xml",
        "logback.groovy",
        "logback-test.groovy",
        "log4j.properties",
        "log4j.xml",
        "log4j2.xml",
        "log4j2.properties",
        "log4j2-test.xml",
        "log4j2-test.properties",
        "simplelogger.properties",
        "commons-logging.properties",
        "logging.properties",
        "application.conf",
        "application.yml",
    )

// Merge META-INF/services files from all JARs (Flyway core + database plugins).
// Shadow 9 defaults to DuplicatesStrategy.EXCLUDE which discards duplicates before
// transformers can merge them, so we must use INCLUDE here.
afterEvaluate {
    tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        mergeServiceFiles()

        // The proprietary Swissbit SDK is deliberately NOT shaded into this JAR
        // (:pos-tse declares it compileOnly). Class-Path is honoured by `java -jar`
        // and resolves relative to the JAR's own directory, so the deployment layout
        //     pos-server.jar
        //     native/WormAPI.jar
        // works with the existing run.sh / systemd unit unchanged. When the file is
        // absent the JVM simply skips the entry — TSE LOCAL mode then degrades the
        // same way it already does when native/ is missing.
        manifest {
            attributes["Class-Path"] = "native/WormAPI.jar"
        }

        // :pos-tse keeps the JNI libraries under src/main/resources/native/, so they
        // are resources and shadowJar would otherwise embed them in this JAR. Nothing
        // loads them from the classpath — they are always resolved from the filesystem
        // via java.library.path — so embedding is pure duplication (~3.8 MB), and it
        // would put Swissbit binaries inside the public artifact. Ship them only as
        // the on-disk native/ folder, which packageForDeployment controls.
        exclude("native/**")

        // Logging/server configuration: ours, and only ours. INCLUDE above (needed for the
        // META-INF/services merge) also keeps every OTHER duplicate path, and a duplicate name in a
        // jar resolves to the LAST entry (java.util.zip's lookup). swagger-codegen-generators
        // (pulled in by ktor-server-openapi) ships a root logback.xml with `<root level="error">`
        // and only a console appender, written after ours — so the packaged server logged ERROR
        // only and never wrote application.log / error.log / client-error.log (F66).
        // Per-path EXCLUDE keeps the first copy, which is the project's own resource (project
        // output precedes dependency jars); verifyShadowJarLoggingConfig proves it on every build.
        filesMatching(listOf("logback.xml", "application.yaml")) {
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        }
        // Configs we do not ship but a dependency could, each of which would override or hijack
        // ours: logback reads logback-test.xml BEFORE logback.xml; Ktor would pick up a stray
        // application.conf. Root level only — these patterns do not match nested resources.
        exclude(shadowJarForeignRootConfigs)
    }
}

tasks.test {
    useJUnitPlatform {
        // The default suite is H2-only. PostgreSQL-backed tests (zonky embedded PG, or a real
        // backup restored into Docker) are opt-in via their own tasks below — excluding the tags
        // here keeps `./gradlew :pos-server:test` green even on a machine with no PG binary.
        excludeTags("postgres", "realdata")
    }
    jvmArgs("-Djava.library.path=$tseNativeLibPath")
    environment("LD_LIBRARY_PATH", tseNativeLibPath)
    // Restart test JVM periodically to prevent H2 in-memory database accumulation
    forkEvery = 5
}

// `Snapshots` re-records only when RECORD_SNAPSHOTS=true. A Test task captures its whole environment at
// configuration time and the configuration cache stores it, so without a tracked input a later run with the
// variable unset reuses a cached `true` and silently re-records. Reading it through a provider makes the
// variable a configuration-cache input (a change invalidates the entry) and pins it explicitly per task.
tasks.withType<Test>().configureEach {
    environment("RECORD_SNAPSHOTS", providers.environmentVariable("RECORD_SNAPSHOTS").getOrElse("false"))
}

// PostgreSQL-backed migration-chain tests (zonky embedded PG). Deliberately NOT wired into
// `check`/`build`: a machine missing the platform binary must never redden a normal build. CI
// invokes this explicitly (`./gradlew :pos-server:postgresTest`). See src/test/README-real-data.md.
val postgresTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs @Tag(\"postgres\") tests (migration chain) against an embedded zonky PostgreSQL."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("postgres")
    }
    jvmArgs("-Djava.library.path=$tseNativeLibPath")
    environment("LD_LIBRARY_PATH", tseNativeLibPath)
    // One embedded PG per test class (JUnit PER_CLASS lifecycle) — no per-method forking needed.
    shouldRunAfter(tasks.test)
}

// The delta-update tests that run against the REAL fat jar rather than a three-entry fixture: the
// jar-structure parser on 44,000 entries with duplicate names and data descriptors, the planner
// against it, the reconstructor rebuilding it byte for byte, and the .cdir.gz asset the build ships.
// They are the strongest evidence the feature has, and they were skipping everywhere — JarFixtures
// gates them on build/libs/*-all.jar with assumeTrue, and no CI step ever built one.
//
// dependsOn("shadowJar") is what makes the task honest: the artifact exists by construction, so a
// green run means the tests ran. The doFirst is the belt: an assumption skip reports as PASSED, so
// without it a renamed archive would turn this gate into a no-op nobody would notice.
//
// Tagged rather than named individually — a gate that lists tests silently stops covering the next
// one somebody adds. The tag is NOT excluded from the default `test` task: there the assumeTrue
// still applies, so the normal suite keeps passing on a machine that never built the fat jar, and
// covers these tests for free on one that did.
val fatjarTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs @Tag(\"fatjar\") tests (delta update against the real shadowJar artifact)."
    dependsOn("shadowJar")
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("fatjar")
    }
    jvmArgs("-Djava.library.path=$tseNativeLibPath")
    environment("LD_LIBRARY_PATH", tseNativeLibPath)
    // JarFixtures resolves build/libs relative to the working directory, which for a Test task is
    // the project directory — do not override it. Captured into a local val rather than read off
    // the script inside doFirst: an action that touches `projectDir` holds a reference to the build
    // script, which the configuration cache refuses to serialize.
    val libsDir = layout.projectDirectory.dir("build/libs").asFile
    doFirst {
        val artifacts = libsDir.listFiles().orEmpty().filter { it.name.endsWith("-all.jar") }
        check(artifacts.isNotEmpty()) {
            "no shadowJar in $libsDir — fatjarTest would skip every test it is meant to gate"
        }
    }
    shouldRunAfter(tasks.test)
}

// Local-only real-data integration check: restores a real backup into PostgreSQL (into the CURRENT
// Exposed schema), replays ONLY the round-5/7 DATA migrations V95..V97 over the real rows (a FULL
// V77..Vmax replay is impossible from a data-only restore — see RealDataMigrationCheck's FINDING),
// then runs a DSFinV-K export over the real closings. Defaults to an EMBEDDED zonky PG (no Docker
// needed); uses Docker only when POS_TEST_PG_URL is set or one is already reachable. Skips loudly
// when POS_TEST_BACKUP is absent. Never runs in CI (the backup carries PII).
val realdataTest by tasks.registering(Test::class) {
    group = "verification"
    description =
        "Runs @Tag(\"realdata\") tests (restore real backup into embedded/Docker PG, replay the round-5/7 DATA migrations V95..V97 over the real rows, then DSFinV-K export)."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("realdata")
    }
    jvmArgs("-Djava.library.path=$tseNativeLibPath")
    environment("LD_LIBRARY_PATH", tseNativeLibPath)
    // The backup is read into memory whole (86 MB and growing) alongside an embedded PostgreSQL and
    // a full DSFinV-K export in the same JVM. On the default test heap this OOMs *after* the check
    // has run end to end, and Gradle then records the completed testcase as a bare <skipped/> with
    // no message — so the check silently reported nothing on every run while appearing to be a
    // benign environment skip. Sized well above the observed peak; raise it if the archive grows.
    maxHeapSize = "3g"
    // Resolve the default relative backup path (test-data/real-backup.zip) against the REPO ROOT,
    // where the gitignored test-data/ lives — not the module dir Gradle would otherwise use.
    workingDir = rootProject.projectDir
    shouldRunAfter(tasks.test)
}

// Guards the exclusions declared at the embedded-postgres dependency above. A transitive
// re-introduction changes no source line, so review cannot catch it and the only symptom is a
// fatter jar (as of 2026-09, 158.4 MB -> 114.1 MB) nobody weighs. Assert the set in the build
// instead, where it fails loudly.
val verifyDatabaseBinaries by tasks.registering {
    group = "verification"
    description = "Fails if the runtime classpath carries embedded-PostgreSQL binaries beyond linux"

    val runtimeModules =
        configurations.named("runtimeClasspath").map { config ->
            config.incoming.artifacts.artifacts
                .map { it.id.componentIdentifier }
                .filterIsInstance<ModuleComponentIdentifier>()
                .filter { it.group == "io.zonky.test.postgres" }
                .map { "${it.group}:${it.module}" }
        }

    doLast {
        val expected =
            setOf(
                "io.zonky.test.postgres:embedded-postgres-binaries-linux-amd64",
            )
        val found = runtimeModules.get().toSet()
        val unexpected = found - expected
        val missing = expected - found
        check(unexpected.isEmpty()) {
            "Unexpected embedded-PostgreSQL binaries on the runtime classpath: " +
                "${unexpected.sorted()}. Each ships inside pos-server.jar. Exclude it, or add it " +
                "to the expected set in verifyDatabaseBinaries with a reason."
        }
        check(missing.isEmpty()) {
            "Missing embedded-PostgreSQL binaries: ${missing.sorted()}. A runtimeOnly declaration " +
                "was removed, or an exclude above is too broad. The release jar must carry the " +
                "linux deployment binaries or the server cannot start its database."
        }
    }
}

// Wired to `check` AND to the packaging path: the release workflow runs test, postgresTest and
// packageForDeployment, never `check`, so a check-only guard would let the regression ship.
// shadowJar is the tighter attachment — it is the task that actually embeds the binaries, and
// packageForDeployment reaches it through buildFatJar.
tasks.named("check") { dependsOn(verifyDatabaseBinaries) }

tasks.named("shadowJar") { dependsOn(verifyDatabaseBinaries) }

// F66 regression guard: inspects the BUILT fat jar, because the defect lived only there — the
// classpath of `run` and of every test resolves the project's logback.xml first, so no test could
// see that pos-server.jar served a dependency's copy. Fails when the jar holds more than one
// logback.xml / application.yaml, when the one it holds is not byte-identical to ours, or when a
// foreign root config from shadowJarForeignRootConfigs slipped back in.
//
// Wired as a finalizer of shadowJar (so packageForDeployment, jpackageServer, fatjarTest and both
// release-workflow packaging steps all run it) and to `check`.
val verifyShadowJarLoggingConfig by tasks.registering {
    group = "verification"
    description = "Fails if the fat jar's logging/server config is not exactly the project's own"
    val jar = tasks.named<Jar>("shadowJar").flatMap { it.archiveFile }
    val resources = layout.projectDirectory.dir("src/main/resources")
    val owned = listOf("logback.xml", "application.yaml")
    val foreign = shadowJarForeignRootConfigs
    inputs.file(jar)
    inputs.files(owned.map { resources.file(it) })
    doLast {
        val counts = mutableMapOf<String, Int>()
        val contents = mutableMapOf<String, ByteArray>()
        val archive = jar.get().asFile
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                val name = entry.name
                if (name in owned || name in foreign) {
                    counts[name] = (counts[name] ?: 0) + 1
                    contents[name] = zip.readBytes()
                }
            }
        }
        val strays = foreign.filter { (counts[it] ?: 0) > 0 }
        check(strays.isEmpty()) {
            "pos-server.jar contains foreign root config(s) $strays from a dependency. They would " +
                "override the project's logging/server configuration — extend the shadowJar exclude."
        }
        owned.forEach { name ->
            check(counts[name] == 1) {
                "pos-server.jar contains ${counts[name] ?: 0} copies of $name, expected exactly 1. The " +
                    "JVM serves the LAST duplicate, so a dependency's copy would silently replace ours (F66)."
            }
            check(contents.getValue(name).contentEquals(resources.file(name).asFile.readBytes())) {
                "pos-server.jar's $name is not the project's src/main/resources/$name — a dependency's " +
                    "copy won the shadowJar duplicate handling (F66)."
            }
        }
        logger.lifecycle("verifyShadowJarLoggingConfig: ${owned.joinToString()} are the project's own, no foreign root configs")
    }
}

tasks.named("shadowJar") { finalizedBy(verifyShadowJarLoggingConfig) }
tasks.named("check") { dependsOn(verifyShadowJarLoggingConfig) }

// Distribution packaging: include native TSE libraries alongside the JAR
distributions {
    main {
        contents {
            from(project(":pos-tse").file("src/main/resources/native")) {
                into("native")
            }
        }
    }
}

// Create a minimal JRE using jlink with only the modules the server needs
tasks.register<Exec>("jlinkRuntime") {
    group = "distribution"
    description = "Creates a minimal JRE using jlink for bundled deployment"

    val outputDir =
        layout.buildDirectory
            .dir("jre")
            .get()
            .asFile

    doFirst {
        // Clean previous output — jlink fails if directory exists
        if (outputDir.exists()) outputDir.deleteRecursively()
    }

    // jlinks the JDK running Gradle. In the release workflow that is Eclipse Temurin 21
    // (actions/setup-java, distribution: temurin) — what THIRD-PARTY-LICENSES.txt declares for jre/.
    executable = "${System.getProperty("java.home")}/bin/jlink"
    setArgs(
        listOf(
            "--add-modules",
            listOf(
                "java.base",
                "java.desktop", // AWT/ImageIO (used by PDFBox, QR code)
                "java.logging",
                "java.management", // JMX (HikariCP monitoring)
                "java.naming", // JNDI (JDBC driver lookup)
                "java.net.http",
                "java.security.jgss",
                "java.sql", // JDBC
                "java.xml", // XML processing (DATEV export)
                "jdk.charsets", // Legacy codepages (ESC/POS printer Cp860 etc.)
                "jdk.crypto.ec", // TLS/SSL (HTTPS, JWT)
                "jdk.unsupported", // Netty native transport
            ).joinToString(","),
            "--output",
            outputDir.absolutePath,
            "--strip-debug",
            "--compress",
            "zip-6",
            "--no-header-files",
            "--no-man-pages",
        ),
    )
}

// Generate deployment metadata files (VERSION, README.txt)
val generateDeploymentMeta by tasks.registering {
    val outputDir = layout.buildDirectory.dir("deploy-meta")
    val versionValue = appVersion
    val nameValue = appName
    val javaVersion =
        java.toolchain.languageVersion
            .get()
            .asInt()
    val bundlesTseRuntime = (project.findProperty("includeTseRuntime") as? String)?.toBoolean() ?: true
    outputs.dir(outputDir)
    inputs.property("bundlesTseRuntime", bundlesTseRuntime)

    doLast {
        val nativeLine =
            if (bundlesTseRuntime) {
                "  native/              TSE runtime: WormAPI.jar + JNI libraries (LOCAL mode)"
            } else {
                "  native/              NOT INCLUDED — supply your own TSE runtime, see below"
            }
        val tseSection =
            if (bundlesTseRuntime) {
                ""
            } else {
                """
            |
            |TSE hardware support (required for German fiscal operation):
            |  This build does not ship the proprietary Swissbit SDK. To enable local
            |  TSE hardware access, create a native/ folder next to pos-server.jar:
            |
            |    native/WormAPI.jar              (Swissbit Java SDK)
            |    native/linux64/libWormAPI.so
            |    native/linux64/libWormAPIJni.so
            |
            |  Use the linux32/ variants on a 32-bit host. File names must match exactly.
            |
            |  Without them the server still starts and every non-fiscal feature works,
            |  but the TSE is NOT optional for a German till:
            |    - LOCAL connection mode cannot be selected (the server rejects it).
            |    - In NORMAL mode terminals cannot register a TSE client, so bills are
            |      refused before creation — the till cannot sell.
            |    - Transactions otherwise follow the configured failure behaviour:
            |      QUEUE = sign later (Nachsignieren), FAIL = reject.
            |  Germany (KassenSichV) requires a certified TSE. SIMULATION mode produces
            |  fake signatures and is for development only. Outside Germany, set the TSE
            |  mode to DISABLED.
                """.trimMargin()
            }

        @Suppress("UNCHECKED_CAST")
        val buildDate = String.format("%tF", System.currentTimeMillis())

        outputDir.get().file("VERSION").asFile.writeText(
            "$versionValue\n",
        )

        outputDir.get().file("README.txt").asFile.writeText(
            """
            |$nameValue v$versionValue
            |Built: $buildDate
            |Java:  $javaVersion (bundled JRE included)
            |
            |Requires x86_64 (amd64). This is not a preference:
            |  - the bundled database binaries are x86_64 only, and
            |  - the TSE driver ships x86 libraries only and runs inside the server process,
            |    so an ARM host cannot sign receipts whatever database it uses.
            |On ARM the server refuses to start the embedded database and says so.
            |
            |Directory structure:
            |  pos-server.jar       Server application (fat JAR)
            |  jre/                 Bundled minimal Java runtime
            |$nativeLine
            |  run.sh               Linux startup script
            |  install-service.sh   Linux systemd service installer
            |  provision-node.sh    Optional: slims a standard Ubuntu desktop into a POS device
            |                       (offered by install-service.sh; config: pos-node.conf.example)
            |  VERSION              Version file
            |  README.txt           This file
            |  LICENSE.md           License of this package (MIT; proprietary parts named)
            |  THIRD-PARTY-LICENSES.txt  Licenses of the bundled third-party software
            |$tseSection
            |
            |Quick start:
            |  chmod +x run.sh && ./run.sh
            |
            |Install as system service:
            |  sudo ./install-service.sh
            |  Optional log-search login: the installer asks once; later 'sudo ./install-service.sh --maintainer'
            |  (or --maintainer-off). See './install-service.sh --help'.
            |
            |Configuration:
            |  DATABASE_MODE=embedded (default) or DATABASE_MODE=external
            |  Application config: ~/POS Server/application.yaml
            |  Logs:               ~/POS Server/.data/log/
            |  Database:           ~/POS Server/.data/postgresql/
            """.trimMargin(),
        )
    }
}

// Package everything for deployment
// Creates: build/deploy/
//          ├── pos-server.jar       (fat JAR via Ktor plugin)
//          ├── jre/                 (minimal JRE via jlink)
//          ├── native/              (JNI libraries for TSE LOCAL mode)
//          │   └── linux64/
//          ├── run.sh               (Linux startup script)
//          ├── install-service.sh   (Linux systemd installer)
//          ├── VERSION
//          ├── README.txt
//          └── THIRD-PARTY-LICENSES.txt  (generateThirdPartyNotices)
// Whether packageForDeployment should also build + bundle the Android APK,
// Linux .deb, update zip, and manifest.json into build/deploy/clients/.
// Default true (local builds). CI passes -PbundleClients=false because the
// release workflow builds APK/DEB in dedicated steps and uploads them separately.
val bundleClients = (project.findProperty("bundleClients") as? String)?.toBoolean() ?: true

// Whether the deployment package embeds the proprietary TSE runtime (WormAPI.jar +
// JNI native libraries). Default true (private/internal releases). The public
// open-source release builds with -PincludeTseRuntime=false; operators then supply
// their own Swissbit SDK under native/ — see the public README for exact filenames.
val includeTseRuntime = (project.findProperty("includeTseRuntime") as? String)?.toBoolean() ?: true

// --- Web admin panel (adminApp / Compose-wasm) ---------------------------------------------------
// The panel is served by pos-server at /admin (see infrastructure/plugins/Routing.kt). Building the
// wasmJs production bundle is slow and pulls the whole Compose toolchain, so it is folded into the
// server JAR only when we actually package for deployment — never on a plain `run`/`test`/`buildFatJar`
// (dev uses the webpack dev server: `./gradlew :ui-backoffice:wasmJsBrowserDevelopmentRun`). It auto-enables
// when packageForDeployment/jpackageServer is on the command line (this is what the release workflow
// runs); override explicitly with -PbundleAdminPanel=true|false.
val bundleAdminPanel =
    (project.findProperty("bundleAdminPanel") as? String)?.toBoolean()
        ?: gradle.startParameter.taskNames.any {
            it.contains("packageForDeployment") || it.contains("jpackageServer")
        }

// Source maps are DevTools-only (the largest is 37 MB) and expose the sources, so the deployment/release
// package drops them from both web bundles; a bundle built any other way (e.g. -PbundleAdminPanel=true
// for a local check) keeps them. Override explicitly with -PstripSourceMaps=true|false.
val stripSourceMaps =
    (project.findProperty("stripSourceMaps") as? String)?.toBoolean()
        ?: gradle.startParameter.taskNames.any {
            it.contains("packageForDeployment") || it.contains("jpackageServer")
        }

// Writes .br/.gz siblings of the compressible files in a staged web bundle (scripts/web/precompress.mjs,
// run with the Node that the Kotlin wasm build already provisioned) so pos-server serves them as-is
// instead of gzipping several MB per request. Runs as the last step of the Sync task that stages the
// bundle, because Sync deletes anything in its target that is not in its source.
val precompressScript = rootProject.layout.projectDirectory.file("scripts/web/precompress.mjs")
val wasmNodeExecutable = rootProject.extensions.getByType<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec>().executable

fun Task.precompressInto(dir: Provider<Directory>) {
    // Locals, not the script-level vals: a doLast that reads those captures the script object,
    // which the configuration cache cannot serialize.
    val script = precompressScript.asFile
    val node = wasmNodeExecutable
    inputs.file(script)
    doLast {
        val process =
            ProcessBuilder(node.get(), script.absolutePath, dir.get().asFile.absolutePath)
                .inheritIO()
                .start()
        check(process.waitFor() == 0) { "precompress.mjs failed" }
    }
}

// The copy lands the dist under an `admin/` subfolder so, added as a resource root, it maps to the
// classpath path `admin/` that `staticResources("/admin", "admin")` reads.
val adminPanelResourcesDir = layout.buildDirectory.dir("generated-resources/admin-panel")

// Sync, not Copy: webpack names the wasm bundles by content hash, so every adminApp
// change produces a new filename. Copy only ever adds, so the previous bundles stayed
// in the staging directory forever and were baked into the fat JAR — ~23 MB of
// unreachable bytes after two rebuilds, growing with every subsequent one. Sync mirrors
// the source and deletes what no longer exists there.
val copyAdminPanel by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Copies the adminApp wasmJs production distribution into pos-server resources (served at /admin)"
    dependsOn(":ui-backoffice:wasmJsBrowserDistribution")
    // Never bundle a dist that carries build-machine paths (root build.gradle.kts).
    dependsOn(":ui-backoffice:verifyWasmDistHasNoBuildPaths")
    from(project(":ui-backoffice").layout.buildDirectory.dir("dist/wasmJs/productionExecutable"))
    // A missing map is a harmless 404 in DevTools.
    if (stripSourceMaps) exclude("**/*.map")
    into(adminPanelResourcesDir.map { it.dir("admin") })
    // Serve the SPA under the /admin/ sub-path: rewrite the generated index.html to carry
    // <base href="/admin/"> so every relative asset (adminApp.js, *.wasm, composeResources/**),
    // all resolved against document.baseURI at runtime, points under /admin/ regardless of whether
    // the browser landed on /admin or /admin/.
    filesMatching("index.html") {
        filter { line ->
            if (line.contains("<head>")) "$line\n    <base href=\"/admin/\">" else line
        }
    }
    precompressInto(adminPanelResourcesDir.map { it.dir("admin") })
}

if (bundleAdminPanel) {
    sourceSets {
        main {
            resources.srcDir(adminPanelResourcesDir)
        }
    }
    tasks.named("processResources") {
        dependsOn(copyAdminPanel)
    }
}

// --- POS browser till (composeApp / Compose-wasm) ------------------------------------------------
// The till client itself, compiled for wasm and served at /pos (see infrastructure/plugins/Routing.kt).
// It is a shipped feature: browser stations, enabled per shop by the `browser_stations` setting. The
// route answers 404 until a shop enables at least one browser role, so bundling it is inert by default.
//
// Bundled exactly like bundleAdminPanel above: the wasmJs production build is slow, so it is folded
// into the server JAR only when packageForDeployment/jpackageServer is on the command line (what the
// release workflow runs) — never on a plain `run`/`test`/`buildFatJar`. Override explicitly with
// -PbundlePosWeb=true|false (false forces it off even when packaging).
//
// Dev uses the webpack dev server on port 8082:
//   ./gradlew :ui-till:wasmJsBrowserDevelopmentRun
// Note the known trap: that task ignores compileKotlinWasmJs, so
// wasmJsDevelopmentExecutableCompileSync must run first or the browser silently serves the
// previous build.
val bundlePosWeb =
    (project.findProperty("bundlePosWeb") as? String)?.toBoolean()
        ?: gradle.startParameter.taskNames.any {
            it.contains("packageForDeployment") || it.contains("jpackageServer")
        }

val posWebResourcesDir = layout.buildDirectory.dir("generated-resources/pos-web")

// Sync, not Copy — same reason as copyAdminPanel: webpack content-hashes the wasm chunks, so Copy
// would accumulate every past bundle in the staging directory and bake them all into the JAR.
val copyPosWeb by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Copies the composeApp wasmJs production distribution into pos-server resources (served at /pos)"
    dependsOn(":ui-till:wasmJsBrowserDistribution")
    // Never bundle a dist that carries build-machine paths (root build.gradle.kts).
    dependsOn(":ui-till:verifyWasmDistHasNoBuildPaths")
    from(project(":ui-till").layout.buildDirectory.dir("dist/wasmJs/productionExecutable"))
    // A missing map is a harmless 404 in DevTools.
    if (stripSourceMaps) exclude("**/*.map")
    into(posWebResourcesDir.map { it.dir("pos-web") })
    // Same <base href> rewrite as the admin panel: every asset the bundle loads (composeApp.js, the
    // content-hashed .wasm chunks, composeResources/**) is a relative URL resolved against
    // document.baseURI, so without this they resolve against / and 404.
    filesMatching("index.html") {
        filter { line ->
            if (line.contains("<head>")) "$line\n    <base href=\"/pos/\">" else line
        }
    }
    precompressInto(posWebResourcesDir.map { it.dir("pos-web") })
}

if (bundlePosWeb) {
    sourceSets {
        main {
            resources.srcDir(posWebResourcesDir)
        }
    }
    tasks.named("processResources") {
        dependsOn(copyPosWeb)
    }
}

// The structure asset a delta update fetches instead of the whole jar. Runs the shipped
// JarStructureWriter rather than reimplementing directory parsing in the build script: the client
// parses the very bytes this writes, so one implementation must serve both.
// Its own output directory rather than deploy-meta/: generateDeploymentMeta declares that whole
// DIRECTORY as its output, so writing here too would nest one task's output inside another's.
// It stays there: the asset is published standalone from build/delta-meta/ and is deliberately NOT
// copied into build/deploy/ — see packageForDeployment's dependsOn for why.
val writeJarStructure by tasks.registering(JavaExec::class) {
    group = "distribution"
    description = "Writes build/delta-meta/pos-server.jar.cdir.gz, the jar-structure asset a delta update client fetches"

    // No dependsOn("shadowJar"): inputs.file on the archive provider already carries the producer.
    val jar = tasks.named<Jar>("shadowJar").flatMap { it.archiveFile }
    val target = layout.buildDirectory.file("delta-meta/pos-server.jar.cdir.gz")

    mainClass.set("org.example.project.features.update.data.service.JarStructureWriter")
    classpath = sourceSets.main.get().runtimeClasspath
    argumentProviders.add {
        listOf(jar.get().asFile.absolutePath, target.get().asFile.absolutePath)
    }
    inputs.file(jar)
    outputs.file(target)
}

tasks.register<Copy>("packageForDeployment") {
    group = "distribution"
    description = "Creates a deployment package with JAR, bundled JRE, native libs, and startup scripts"
    // writeJarStructure is a dependency but its output is deliberately NOT copied into the package:
    // pos-server.jar.cdir.gz is a standalone RELEASE ASSET, fetched over HTTP by a client planning a
    // delta. Nothing on an installed host ever reads it, so shipping it put ~0.9 MB into every
    // install directory for nothing — and, worse, that copy is the one the release workflow's TSE
    // gate does NOT decompress (its *.gz arm only sees the top-level asset), so it travelled inside
    // the server zip uninspected. The release workflow takes it from build/delta-meta/ instead; the
    // dependency is what guarantees that path exists after packaging, and writeJarStructure declares
    // the shadowJar archive as its input, so the asset always describes the jar this package ships.
    dependsOn("buildFatJar", "jlinkRuntime", generateDeploymentMeta, writeJarStructure, verifyShadowJarLoggingConfig)
    // Third-party notices at the package root. generateThirdPartyNotices also re-runs the license
    // policy, so a dependency nobody has cleared cannot be packaged (docs/claude/deployment.md). The
    // file is identical for the private and the public package: the TSE runtime is never a Gradle
    // dependency, so the notices never name it.
    dependsOn(":generateThirdPartyNotices")

    if (bundleClients) {
        dependsOn(":ui-till-android:assembleRelease", "generateClientManifest")
        if (org.gradle.internal.os.OperatingSystem
                .current()
                .isLinux
        ) {
            dependsOn(":ui-till:packageReleaseDeb", "packageClientUpdateZip")
        }
    }

    // Copy the Ktor fat JAR (produced by shadowJar)
    from(tasks.named("shadowJar").map { it.outputs.files }) {
        rename { "pos-server.jar" }
    }

    // Copy the minimal JRE
    from(layout.buildDirectory.dir("jre")) {
        into("jre")
    }

    if (includeTseRuntime) {
        // Copy native JNI libraries from pos-tse module
        from(project(":pos-tse").file("src/main/resources/native")) {
            into("native")
        }

        // Copy the Swissbit SDK jar alongside them. pos-server.jar picks it up
        // through its Class-Path manifest entry (native/WormAPI.jar).
        from(wormApiJar) {
            into("native")
        }
    }

    // Copy startup and installer scripts
    from("scripts") {
        include("run.sh", "install-service.sh", "provision-node.sh", "pos-node.conf.example", "kiosk/*", "lib/*.sh")
        filePermissions {
            unix("rwxr-xr-x")
        }
    }

    // Caddy reverse proxy (HTTPS, local CA). Built by the release workflow with xcaddy (plain Caddy, no
    // DNS modules) and passed in as -PcaddyDist=<dir> (a relative path resolves against the repo root, matching the
    // workflow's absolute one). Absent in local builds; the installers then refuse to set up HTTPS
    // and say where the binary comes from. A present-but-incomplete dir would otherwise silently
    // ship a package without Caddy, stranding HTTPS-only shops — fail configuration instead.
    providers.gradleProperty("caddyDist").orNull?.let { caddyDist ->
        val caddyDir = rootProject.file(caddyDist)
        check(caddyDir.resolve("caddy-linux-amd64").isFile) {
            "-PcaddyDist=$caddyDist must contain caddy-linux-amd64 (built by the release workflow)"
        }
        from(caddyDir) {
            into("caddy")
            filePermissions {
                unix("rwxr-xr-x")
            }
        }
    }

    // Copy generated metadata
    from(layout.buildDirectory.dir("deploy-meta")) {
        include("VERSION", "README.txt")
    }

    from(rootProject.layout.buildDirectory.file("third-party/THIRD-PARTY-LICENSES.txt"))

    // The package's own license file differs per release set: the internal package carries the
    // proprietary Swissbit SDK in native/ and must say that it is NOT under the MIT License, the
    // public package says the TSE runtime is absent and where operators get it. Same flag that
    // decides whether native/ is copied, so the text can never disagree with the contents.
    from(rootProject.file("config/licenses/package/LICENSE-${if (includeTseRuntime) "private" else "public"}.md")) {
        rename { "LICENSE.md" }
    }

    if (bundleClients) {
        // Android APK
        from(project(":ui-till-android").layout.buildDirectory.dir("outputs/apk/release")) {
            include("*-release.apk", "*.apk")
            rename { "client-android.apk" }
            into("clients")
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        }

        // Linux artifacts (gated)
        if (org.gradle.internal.os.OperatingSystem
                .current()
                .isLinux
        ) {
            from(project(":ui-till").layout.buildDirectory.dir("compose/binaries/main-release/deb")) {
                include("*.deb")
                rename { "client-linux-x64.deb" }
                into("clients")
                duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            }
            from(layout.buildDirectory.file("client-artifacts/client-linux-x64-update.zip")) {
                into("clients")
            }
        }

        // Manifest
        from(layout.buildDirectory.file("client-artifacts/manifest.json")) {
            into("clients")
        }
    }

    into(layout.buildDirectory.dir("deploy"))
}

// Native installer via jpackage (deb on Linux, dmg on macOS)
// Note: jpackage can only build for the CURRENT OS (no cross-compilation).
// For jar-based deployment without native installer, use `packageForDeployment` instead.
tasks.register<Exec>("jpackageServer") {
    group = "distribution"
    description = "Creates a native OS installer (deb/dmg) using jpackage"
    dependsOn("buildFatJar")

    val jarFile = tasks.named("shadowJar").map { it.outputs.files.singleFile }
    val outputDir =
        layout.buildDirectory
            .dir("jpackage")
            .get()
            .asFile
    val nativeDir = project(":pos-tse").file("src/main/resources/native/$nativeLibDir")

    doFirst {
        outputDir.mkdirs()
    }

    val osName = System.getProperty("os.name").lowercase()
    val osType =
        when {
            osName.contains("linux") -> "deb"
            osName.contains("mac") -> "dmg"
            else -> "app-image"
        }

    executable = "jpackage"
    setArgs(
        listOf(
            "--input",
            jarFile.get().parentFile.absolutePath,
            "--main-jar",
            jarFile.get().name,
            "--name",
            "POS-Server",
            "--app-version",
            version.toString(),
            "--type",
            osType,
            "--dest",
            outputDir.absolutePath,
            "--java-options",
            "-Djava.library.path=${nativeDir.absolutePath}",
            "--java-options",
            "-Xmx512m",
        ),
    )
}

kotlin {
    jvmToolchain(21)
}

// Zips bin/, lib/, share/ from the desktop app-image into client-linux-x64-update.zip (Linux only).
// The app-image is produced by :ui-till:createReleaseDistributable under
// ui/till/build/compose/binaries/main-release/app/zenbon-pos/
tasks.register<Zip>("packageClientUpdateZip") {
    group = "distribution"
    description = "Creates client-linux-x64-update.zip from the desktop app-image (Linux only)"
    dependsOn(":ui-till:createReleaseDistributable")
    onlyIf {
        org.gradle.internal.os.OperatingSystem
            .current()
            .isLinux
    }

    val appImageDir =
        project(":ui-till")
            .layout.buildDirectory
            .dir("compose/binaries/main-release/app/zenbon-pos")

    from(appImageDir) {
        include("bin/**", "lib/**", "share/**")
    }
    archiveFileName.set("client-linux-x64-update.zip")
    destinationDirectory.set(layout.buildDirectory.dir("client-artifacts"))
}

// Computes sha256 for the Android APK and Linux update zip, then writes manifest.json.
// Output: build/client-artifacts/manifest.json
tasks.register("generateClientManifest") {
    group = "distribution"
    description = "Computes sha256 for client artifacts and writes manifest.json"
    dependsOn(":ui-till-android:assembleRelease", "packageClientUpdateZip")

    val outputFile = layout.buildDirectory.file("client-artifacts/manifest.json")
    outputs.file(outputFile)

    // Everything the action needs is captured here, at configuration time. Reading `version` or
    // calling `project(":ui-till-android")` inside doLast references the build script and the project
    // model, which the configuration cache (enabled in gradle.properties) cannot store — the task
    // failed with "cannot serialize Gradle script object references" on every local
    // packageForDeployment with the default bundleClients=true.
    val versionStr = version.toString()
    val androidApkDirProvider = project(":ui-till-android").layout.buildDirectory.dir("outputs/apk/release")
    val linuxZipProvider = layout.buildDirectory.file("client-artifacts/client-linux-x64-update.zip")

    doLast {
        val androidApk =
            androidApkDirProvider
                .get()
                .asFile
                .listFiles { f -> f.extension == "apk" }
                ?.firstOrNull()

        val linuxZip = linuxZipProvider.get().asFile

        fun sha256(f: java.io.File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { b -> "%02x".format(b) }
        }

        val entries = mutableMapOf<String, Map<String, Any?>>()

        if (androidApk != null && androidApk.exists()) {
            entries["android"] =
                mapOf(
                    "version" to versionStr,
                    "sha256" to sha256(androidApk),
                    "sizeBytes" to androidApk.length(),
                    "file" to "client-android.apk",
                    "mandatory" to false,
                )
        }

        if (linuxZip.exists()) {
            entries["linux-x64"] =
                mapOf(
                    "version" to versionStr,
                    "sha256" to sha256(linuxZip),
                    "sizeBytes" to linuxZip.length(),
                    "file" to "client-linux-x64-update.zip",
                    "mandatory" to false,
                )
        }

        val out = outputFile.get().asFile
        out.parentFile.mkdirs()
        out.writeText(groovy.json.JsonBuilder(entries).toPrettyString())
        logger.lifecycle("manifest.json written to ${out.absolutePath} — entries: ${entries.keys}")
    }
}
