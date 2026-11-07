// Fix commons-compress version conflict with Spring Boot Gradle plugin
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.apache.commons:commons-compress:1.27.1")
    }
}

plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.androidKmpLibrary) apply false
    alias(libs.plugins.composeHotReload) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.buildkonfig) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.cyclonedx)
}

// Apply ktlint to every module except the safety-critical `pos-tse` driver,
// which must not be reformatted (see CLAUDE.md protected-module boundary).
subprojects {
    if (name != "pos-tse") {
        apply(plugin = "org.jlleitschuh.gradle.ktlint")
        // Generated sources (Compose resources, SQLDelight) are excluded via the
        // `[**/build/**] ktlint = disabled` section in the root .editorconfig,
        // which ktlint evaluates per-file at lint time (timing-independent, unlike
        // the Gradle-level source filter).
    }
}

// --- Relative source paths in Kotlin/JS + Kotlin/Wasm klibs --------------------------------------
// A klib records each source file by its ABSOLUTE path, and the Wasm backend copies that path into
// the shipped .wasm: a callable reference to a file-private or local declaration (`::importPdfJs`,
// a `private val x by lazy`) carries its IdSignature, "[ File '<module>/home/<user>/<checkout>/...kt'
// <- ... ]", as a runtime string. That put the build machine's user name and checkout layout into
// the /admin and /pos bundles. Recording the paths relative to the repository root removes it at the
// cause for every module and every future private reference (verifyWasmDistHasNoBuildPaths, below,
// is the guard). Kotlin2JsCompile covers both the klib compilations and the executable link.
subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.Kotlin2JsCompile>().configureEach {
        compilerOptions.freeCompilerArgs.add("-Xklib-relative-path-base=${rootDir.absolutePath}")
    }
}

// --- No build-machine paths in the shipped wasm bundles ------------------------------------------
// pos-server ships adminApp's and composeApp's production dist (/admin, /pos). The dist is checked with
// its source maps included; the deployment/release package then drops the maps (stripSourceMaps in
// pos-server/build.gradle.kts), while any other bundled build keeps them. Two causes once leaked this machine's absolute path into it — webpack's inlining of
// `import.meta.url` (fixed by webpack.config.d/importMetaUrl.js in each app) and klib source paths
// (fixed above). This check fails the distribution build if any shipped file still names the
// checkout, the user's home or the Gradle home, or contains any other home-directory-like path.
abstract class WasmDistPathLeakCheck : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val distFiles: ConfigurableFileCollection

    /** Exact strings that must never appear: the checkout, user home and Gradle home of THIS build. */
    @get:Input
    abstract val forbiddenPaths: ListProperty<String>

    /**
     * Third-party literals that look like home paths but are not ours, removed before scanning:
     * Emscripten's virtual-FS home (skiko, pdf.js decoders), the paths the publisher of the prebuilt
     * @techstark/opencv-js binary compiled in on their CI, and the source paths three published klibs
     * (Coil, compose-imageloader, the CMP image-crop/QR-scanner libraries) were built with, which reach
     * the .wasm through the same file-private-signature mechanism as ours did. They are their
     * maintainers' paths, fixed in the artifacts we download; a NEW entry here needs that same proof.
     */
    @get:Input
    abstract val allowedLiterals: ListProperty<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun scan() {
        val homeLike = Regex("""/home/|/Users/|[A-Za-z]:\\{1,2}Users\\{1,2}""")
        val findings =
            distFiles.files.sorted().flatMap { file ->
                var text = file.readText(Charsets.ISO_8859_1)
                allowedLiterals.get().forEach { text = text.replace(it, "") }
                val exact = forbiddenPaths.get().filter { it.isNotBlank() && text.contains(it) }.map { "contains '$it'" }
                val generic = homeLike.findAll(text).map { "...${text.substring(maxOf(0, it.range.first - 60), minOf(text.length, it.range.last + 80))}..." }.take(3).toList()
                (exact + generic).map { "${file.name}: $it" }
            }
        report.get().asFile.writeText(findings.joinToString("\n", postfix = "\n"))
        if (findings.isNotEmpty()) {
            throw GradleException(
                "The wasm production dist contains build-machine paths (see ${report.get().asFile}):\n" +
                    findings.take(20).joinToString("\n"),
            )
        }
    }
}

listOf(":ui-backoffice", ":ui-till").forEach { appPath ->
    project(appPath) {
        val distDir = layout.buildDirectory.dir("dist/wasmJs/productionExecutable")
        val verify =
            tasks.register<WasmDistPathLeakCheck>("verifyWasmDistHasNoBuildPaths") {
                group = "verification"
                description = "Fails if the wasm production dist contains this machine's absolute paths."
                distFiles.from(fileTree(distDir))
                val home = System.getProperty("user.home")
                forbiddenPaths.set(
                    listOf(
                        rootDir.absolutePath,
                        rootDir.invariantSeparatorsPath,
                        home,
                        File(home).invariantSeparatorsPath,
                        gradle.gradleUserHomeDir.absolutePath,
                        gradle.gradleUserHomeDir.invariantSeparatorsPath,
                    ).distinct(),
                )
                allowedLiterals.set(
                    listOf(
                        "/home/web_user",
                        "/home/runner/work/opencv-js/",
                        "/Users/colin/coil/",
                        "/Users/runner/work/compose-imageloader/",
                        "/Users/mayurwaghmare/Documents/CMP Libraries/",
                    ),
                )
                report.set(layout.buildDirectory.file("reports/wasm-dist-path-leaks.txt"))
            }
        tasks.matching { it.name == "wasmJsBrowserDistribution" }.configureEach { finalizedBy(verify) }
    }
}

// --- Ktor OpenAPI compiler plugin for the route-carrying feature modules -------------------------
// The plugin derives OpenAPI operation metadata from the routing tree and the KDoc above each
// handler. :pos-server gets it from io.ktor.plugin (see pos-server/build.gradle.kts); these
// library modules define routes too, and without the plugin their endpoints reach the generated
// spec as bare paths with no summary, tag, parameter or response documentation.
//
// The plugin is attached directly instead of by applying io.ktor.plugin, which also brings
// `application` and shadow — their fat-JAR and start-script tasks fail on a library module that
// has no main class. Attaching it to kotlinCompilerPluginClasspathMain also keeps it off the TEST
// compilation, where it ICEs ("Parent of element ... is not initialized"); :pos-server works
// around that same defect by excluding it from kotlinCompilerPluginClasspathTest.
//
// :backoffice-timetracking is deliberately absent — its routes are commented out in Routing.kt, so
// nothing it defines is mounted and the plugin would have no endpoints to describe. Add it here
// if those routes are re-enabled.
val openApiRouteModules =
    setOf(
        "platform-audit",
        "platform-server",
        "ecr-server",
        "fiscal-server",
        "accounting-server",
        "backoffice-server",
        "ecr-customer",
        "accounting-document",
        "ecr-printer",
        "ecr-reservation",
        "ecr-voucher",
    )

subprojects {
    if (name !in openApiRouteModules) return@subprojects

    plugins.withId("org.jetbrains.kotlin.jvm") {
        dependencies {
            add(
                "kotlinCompilerPluginClasspathMain",
                "io.ktor:ktor-compiler-plugin:${libs.versions.ktor.get()}",
            )
            // The plugin rewrites each handler into a describe(...) call, so that function must be
            // resolvable at compile time. io.ktor.plugin adds this artifact implicitly; wiring the
            // compiler plugin by hand means adding it by hand too, or every route file ICEs with
            // "List is empty" out of CallDescribeTransformer.getDescribeFunction.
            add("implementation", libs.ktor.serverRoutingOpenApi.get().toString())
        }
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
            if (name != "compileKotlin") return@configureEach
            compilerOptions.freeCompilerArgs.addAll(
                "-P",
                "plugin:io.ktor.ktor-compiler-plugin:openApiEnabled=true",
                "-P",
                "plugin:io.ktor.ktor-compiler-plugin:openApiCodeInference=true",
                "-P",
                "plugin:io.ktor.ktor-compiler-plugin:openApiOnlyCommented=false",
            )
        }
    }
}
// --- Dependency license check + third-party notices ----------------------------------------------
// Releases must ship only open-source, freely redistributable dependencies. The SHIPPED artifacts are
// described below by the exact configuration that ends up in each one; everything else (tests, build
// tooling, the other platforms of a KMP module) is out of scope by construction.
//
//   :pos-server  runtimeClasspath         -> pos-server.jar (shadowJar input)
//   :ui-till-android  releaseRuntimeClasspath  -> the APK
//   :ui-backoffice    wasmJsRuntimeClasspath   -> the /admin bundle inside pos-server.jar
//   :ui-till  wasmJsRuntimeClasspath   -> the /pos browser-till bundle inside pos-server.jar
//   :ui-till  desktopRuntimeClasspath  -> the Linux .deb
//
// CycloneDX resolves those graphs (configuration-cache safe) and records each component's POM
// license; scripts/licenses/third_party.py normalises them to SPDX ids, applies the policy in
// config/licenses/ and adds the npm packages the wasm bundles carry. See docs/claude/deployment.md.
val shippedConfigurations: Map<String, Map<String, String>> =
    mapOf(
        ":pos-server" to mapOf("runtimeClasspath" to "server"),
        ":ui-till-android" to mapOf("releaseRuntimeClasspath" to "apk"),
        ":ui-backoffice" to mapOf("wasmJsRuntimeClasspath" to "server"),
        ":ui-till" to mapOf("wasmJsRuntimeClasspath" to "server", "desktopRuntimeClasspath" to "deb"),
    )

/**
 * Writes "group:name:version<TAB>file" for every external artifact of one shipped configuration, so
 * generateThirdPartyNotices can lift the NOTICE/LICENSE files out of the very jars that ship.
 */
abstract class ShippedArtifactManifest : DefaultTask() {
    @get:Input
    abstract val entries: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val artifactFiles: ConfigurableFileCollection

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun write() {
        output.get().asFile.writeText(entries.get().sorted().joinToString("\n", postfix = "\n"))
    }
}

allprojects {
    val configs = shippedConfigurations[path]
    tasks.named<org.cyclonedx.gradle.CyclonedxDirectTask>("cyclonedxDirectBom") {
        // Only the shipped projects contribute; library modules reach the SBOM through them.
        enabled = configs != null
        includeConfigs.set(configs?.keys?.toList() ?: emptyList())
        skipConfigs.set(listOf("(?i).*test.*"))
    }
    if (configs == null) return@allprojects
    val manifestTasks =
        configs.keys.map { configName ->
            tasks.register<ShippedArtifactManifest>("${configName}ArtifactManifest") {
                group = "verification"
                description = "Lists the external artifacts of $configName for the third-party notices."
                val view =
                    configurations.named(configName).map { configuration ->
                        configuration.incoming.artifactView {
                            lenient(true)
                            // External modules only: a project dependency would make this build it.
                            componentFilter { it is ModuleComponentIdentifier }
                            // Android: the java-resources view of every AAR/JAR, which is where
                            // META-INF/NOTICE and META-INF/LICENSE live and what the APK packages.
                            if (configName == "releaseRuntimeClasspath") {
                                attributes.attribute(Attribute.of("artifactType", String::class.java), "android-java-res")
                            }
                        }
                    }
                artifactFiles.from(view.map { it.files })
                entries.set(
                    view.flatMap { it.artifacts.resolvedArtifacts }.map { results ->
                        results.mapNotNull { result ->
                            val id = result.id.componentIdentifier as? ModuleComponentIdentifier ?: return@mapNotNull null
                            "${id.group}:${id.module}:${id.version}\t${result.file.absolutePath}"
                        }
                    },
                )
                output.set(layout.buildDirectory.file("third-party/$configName-artifacts.tsv"))
            }
        }
    tasks.register("shippedArtifactManifests") { dependsOn(manifestTasks) }
}

// The policy engine is one stdlib-only Python script shared by both tasks, so the allowlist logic
// exists once for Maven and npm alike (scripts/licenses/tests holds its unit tests).
val licenseScript = layout.projectDirectory.file("scripts/licenses/third_party.py")
val licenseConfigDir = layout.projectDirectory.dir("config/licenses")
// The lockfile kotlinWasmNpmInstall actually installed from (kotlin-js-store/wasm/yarn.lock is its
// committed copy, rewritten by kotlinWasmStoreYarnLock — reading that one would race the store task).
val npmLockFile = layout.buildDirectory.file("wasm/yarn.lock")
val wasmNodeModules = layout.buildDirectory.dir("wasm/node_modules")
val thirdPartyDir = layout.buildDirectory.dir("third-party")
// Each shipped project's direct SBOM, paired with the scopes it ships in ("server,deb").
val directBoms: List<Pair<String, File>> =
    shippedConfigurations.map { (projectPath, configs) ->
        configs.values.toSortedSet().joinToString(",") to
            project(projectPath).layout.buildDirectory.file("reports/cyclonedx-direct/bom.json").get().asFile
    }

// One artifact manifest per shipped configuration, paired with that configuration's scope.
val artifactManifests: List<Pair<String, File>> =
    shippedConfigurations.flatMap { (projectPath, configs) ->
        configs.map { (configName, scope) ->
            scope to project(projectPath).layout.buildDirectory.file("third-party/$configName-artifacts.tsv").get().asFile
        }
    }
val directBomArgs: List<String> = directBoms.flatMap { (scopes, bom) -> listOf("--bom", "$scopes=${bom.absolutePath}") }
val artifactManifestArgs: List<String> =
    artifactManifests.flatMap { (scope, manifest) -> listOf("--artifacts", "$scope=${manifest.absolutePath}") }
val licenseInputsArgs: List<String> =
    listOf(
        "--root",
        rootDir.absolutePath,
        "--config",
        licenseConfigDir.asFile.absolutePath,
        "--npm-lock",
        npmLockFile.get().asFile.absolutePath,
        "--node-modules",
        wasmNodeModules.get().asFile.absolutePath,
    ) + directBomArgs + artifactManifestArgs

fun Exec.licenseTaskInputs() {
    dependsOn(
        shippedConfigurations.keys.map { "$it:cyclonedxDirectBom" },
        shippedConfigurations.keys.map { "$it:shippedArtifactManifests" },
        ":kotlinWasmNpmInstall",
    )
    inputs.file(licenseScript)
    inputs.dir(licenseConfigDir)
    inputs.file(npmLockFile)
    inputs.files(directBoms.map { it.second })
    inputs.files(artifactManifests.map { it.second })
    // Fonts are checked against bundled-components.json, so a new font file must re-run the check.
    inputs.files(fileTree(rootDir) { include("*/src/**/*.ttf", "*/src/**/*.otf", "*/src/**/*.woff", "*/src/**/*.woff2", "ui/*/src/**/*.ttf", "ui/*/src/**/*.otf", "ui/*/src/**/*.woff", "ui/*/src/**/*.woff2", "platform/*/src/**/*.ttf", "platform/*/src/**/*.otf", "platform/*/src/**/*.woff", "platform/*/src/**/*.woff2", "accounting/*/src/**/*.ttf", "accounting/*/src/**/*.otf", "accounting/*/src/**/*.woff", "accounting/*/src/**/*.woff2", "fiscal/*/src/**/*.ttf", "fiscal/*/src/**/*.otf", "fiscal/*/src/**/*.woff", "fiscal/*/src/**/*.woff2", "ecr/*/src/**/*.ttf", "ecr/*/src/**/*.otf", "ecr/*/src/**/*.woff", "ecr/*/src/**/*.woff2", "backoffice/*/src/**/*.ttf", "backoffice/*/src/**/*.otf", "backoffice/*/src/**/*.woff", "backoffice/*/src/**/*.woff2") })
}

tasks.register<Exec>("checkLicenses") {
    group = "verification"
    description = "Fails on any shipped dependency (Maven or npm) whose license is not allowed by config/licenses/."
    licenseTaskInputs()
    val report = thirdPartyDir.map { it.file("license-check.txt") }
    outputs.file(report)
    commandLine(
        listOf("python3", licenseScript.asFile.absolutePath, "check") + licenseInputsArgs +
            listOf("--report", report.get().asFile.absolutePath),
    )
}

tasks.register<Exec>("generateThirdPartyNotices") {
    group = "distribution"
    description = "Writes build/third-party/THIRD-PARTY-LICENSES.txt and the CycloneDX SBOM build/third-party/sbom.json."
    licenseTaskInputs()
    dependsOn("cyclonedxBom")
    val aggregateBom = layout.buildDirectory.file("reports/cyclonedx/bom.json")
    inputs.file(aggregateBom)
    outputs.file(thirdPartyDir.map { it.file("THIRD-PARTY-LICENSES.txt") })
    outputs.file(thirdPartyDir.map { it.file("sbom.json") })
    commandLine(
        listOf("python3", licenseScript.asFile.absolutePath, "notices") + licenseInputsArgs +
            listOf(
                "--aggregate-bom",
                aggregateBom.get().asFile.absolutePath,
                "--product",
                "${providers.gradleProperty("app.name").get()} ${providers.gradleProperty("app.version").get()}",
                "--out-dir",
                thirdPartyDir.get().asFile.absolutePath,
            ),
    )
}

tasks.register<Exec>("checkLayering") {
    group = "verification"
    description = "Fails on a module-layering violation (spec section 5) that is not frozen in config/structure/layering-baseline.txt."
    inputs.dir("config/structure")
    inputs.dir("scripts/structure")
    inputs.file("settings.gradle.kts")
    inputs.files(fileTree(rootDir) { include("*/build.gradle.kts", "ui/*/build.gradle.kts") })
    commandLine("python3", "-I", "scripts/structure/check_layering.py")
}
