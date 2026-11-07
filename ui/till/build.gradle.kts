import com.codingfeline.buildkonfig.compiler.FieldSpec
import org.gradle.api.tasks.PathSensitivity
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File


// App metadata from gradle.properties
val appVersion = property("app.version") as String
val appVersionCode = (property("app.versionCode") as String).toInt()
val appName = property("app.name") as String
val appPackageName = property("app.packageName") as String
val appDescription = property("app.description") as String
val appVendor = property("app.vendor") as String
val email = property("app.email") as String
val appIcon = property("app.icon") as String

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.buildkonfig)
    alias(libs.plugins.sqldelight)
}

sqldelight {
    databases {
        create("PosDatabase") {
            packageName.set("org.example.project.database")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}

buildkonfig {
    packageName = "org.example.project"

    defaultConfigs {
        buildConfigField(FieldSpec.Type.STRING, "APP_VERSION", appVersion)
        buildConfigField(FieldSpec.Type.STRING, "APP_NAME", appName)
        // Ktor logs full request/response bodies — passwords, login secrets, access/refresh tokens —
        // only when a developer opts in with -Ppos.httpLogBodies=true. Off by default, so a release
        // build (release.yml passes no such flag) never writes them to logcat or stdout.
        buildConfigField(
            FieldSpec.Type.BOOLEAN,
            "HTTP_LOG_BODIES",
            ((findProperty("pos.httpLogBodies") as? String)?.toBoolean() ?: false).toString(),
        )
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
        namespace = "org.example.project.composeapp"
        compileSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        androidResources { enable = true }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "composeApp.js"
                devServer =
                    devServer?.copy(
                        // 8081 is adminApp's; two dev servers must not collide.
                        port = 8082,
                    )
            }
        }
        binaries.executable()
    }

    sourceSets {
        androidMain.dependencies {
            // Window insets for proper system bar handling
            implementation(libs.androidx.foundation)
            implementation(libs.androidx.core.ktx.v1150)

            // Koin for Android (koin.android provided transitively by shared module)
            implementation(libs.koin.compose)
            implementation(libs.koin.composeViewModel)

            // Ktor Client Android Engine provided transitively by shared module

            // SQLDelight Android Driver
            implementation(libs.sqldelightDriverAndroid)

            // ML Kit Document Scanner (camera-based PDF scanning)
            implementation(libs.mlkit.document.scanner)

            // Lifecycle Process observer (for foreground service lifecycle awareness)
            implementation(libs.androidx.lifecycle.process)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)

            implementation(libs.compose.ui.test)
        }

        commonMain.dependencies {
            // Compose Multiplatform
            implementation(libs.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.components.ui.tooling.preview)
            implementation(libs.material.icons.extended)

            implementation(libs.androidx.material3.adaptive)
            implementation(libs.androidx.material3.adaptive.layout)
            implementation(libs.androidx.material3.adaptive.navigation)
            implementation(libs.androidx.material3.adaptive.navigation.suite)

            // Lifecycle
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)

            // Navigation
            implementation(libs.androidx.navigation.compose)
            // Coroutines and Koin core provided transitively by shared module

            // Koin Compose extensions (not in shared)
            implementation(libs.koin.compose)
            implementation(libs.koin.composeViewModel)

            // Ktor Client - most dependencies provided transitively by shared module
            // Only add dependencies not in shared:
            implementation(libs.ktor.clientAuth)

            // Logging
            implementation(libs.kermit)

            // Non-suspending lock for the sealed-secret store (SecretProtectingKeyValueStore); compiles for wasmJs.
            implementation(libs.atomicfu)

            // Image Loading (Coil 3)
            implementation(libs.coil3.compose)
            implementation(libs.coil3.network.ktor)

            // QR Code Generation (DSFinV-K compliance for TSE receipts)
            implementation(libs.qrkit.get().toString()) {
                // qr-kit's android variant leaks test libraries (via androidx.compose.ui:ui-test-junit4)
                // into the shipped APK. They are never used at runtime; keep them out (checkLicenses).
                exclude(group = "junit", module = "junit")
                exclude(group = "org.hamcrest")
            }

            // Webview disabled — uncomment when KCEF/CEF browser embedding is needed
            // api(libs.compose.webview.multiplatform)

            // Area api modules
            implementation(projects.platformApi)
            implementation(projects.ecrApi)
            implementation(projects.fiscalApi)
            implementation(projects.accountingApi)
            implementation(projects.backofficeApi)
            implementation(projects.uiShared)
            implementation(projects.platformMoney)
        }

        wasmJsMain.dependencies {
            // The browser's HTTP engine — the JVM/OkHttp engines cannot be used here.
            implementation(libs.ktor.clientJs)
            // kotlinx.browser.localStorage, used by LocalStorageKeyValueStore.
            implementation(libs.kotlinx.browser)
        }

        val desktopMain by getting {
            dependencies {
                // Desktop Compose
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutinesSwing)

                // Logback for desktop logging (same provider as server — respects logback.xml config)
                implementation(libs.logback)

                // Ktor Client JVM Engine provided transitively by shared module

                // Explicitly add kotlinx-datetime for JVM to ensure it's on the classpath
                implementation(libs.kotlinx.datetime)

                // SQLDelight SQLite Driver for JVM (temporary - will be removed after migration to direct PostgreSQL)
                implementation(libs.sqldelightDriverSqlite)

                // Apache PDFBox for PDF printing with native print dialog
                implementation(libs.pdfbox)

                // OS keychain for the local-secrets key (Windows Credential Manager, Linux Secret
                // Service, macOS Keychain). Desktop only: Android uses Android Keystore.
                implementation(libs.javakeyring)
            }
        }

        val desktopTest by getting {
            dependencies {
                implementation(libs.kotlin.test.junit5)
                implementation(libs.junit.jupiter)
                implementation(compose.desktop.currentOs)
                implementation(libs.h2)
                implementation(libs.hikaricp)
                implementation(libs.exposed.core)
                implementation(libs.exposed.dao)
                implementation(libs.exposed.jdbc)
                implementation(libs.exposed.kotlinDatetime)
                implementation(libs.ktor.clientMock)

                // Koin graph verification (KoinModuleGraphTest). JVM-only on purpose: it is a
                // desktopTest dependency, never commonMain/commonTest, so the wasm and Android
                // compilations never see it.
                implementation(libs.koin.test)

                // NOTE: :pos-server is deliberately NOT a dependency here. The desktop client
                // talks to the server over HTTP only, and the in-process harness that needed it
                // (TestApp/TestDatabase, for the long-disabled AuthE2ETest) has been removed.
                // Re-adding it would drag :pos-tse — and the TSE driver surface — back onto the
                // desktop test classpath.
                implementation(libs.ktor.clientContentNegotiation)
                implementation(libs.ktor.serializationKotlinxJson)
            }
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        // Gradle 9 fails if no tests discovered; Android unit test tasks have no tests
        failOnNoDiscoveredTests.set(false)

        // `NoRoleGatesInScreensTest` scans adminApp's sources as well as this module's — adminApp's
        // own tests run in a browser under karma, where there is no filesystem to walk. Gradle
        // cannot infer that, so without this the task stays UP-TO-DATE when adminApp changes and the
        // guard passes by not running. Verified: it reported green against a deliberately planted
        // role gate until this line existed.
        inputs
            .dir(rootProject.layout.projectDirectory.dir("ui/backoffice/src/wasmJsMain/kotlin"))
            .withPropertyName("adminAppSourcesScannedByGuardTests")
            .withPathSensitivity(PathSensitivity.RELATIVE)

        // `SharedKeysAreTranslatedTest` scans the string catalogue as source text. It lives in
        // :platform-api since stage B2-1b (re-exported through :shared). Unlike adminApp above, it
        // IS a dependency here, so a changed key file already alters this task's runtime classpath
        // and would re-trigger it. This states the dependency the guard actually has instead of
        // relying on that side effect staying true — the same failure (task UP-TO-DATE, guard passes
        // by not running) would otherwise return silently the day the catalogue stops being compiled
        // into this module's classpath.
        inputs
            .dir(rootProject.layout.projectDirectory.dir("platform/api/src/commonMain/kotlin/org/example/project/core/presentation/strings"))
            .withPropertyName("sharedStringCatalogueScannedByGuardTests")
            .withPathSensitivity(PathSensitivity.RELATIVE)

        // `NoDroppedUiErrorInShowErrorTest` scans shared-ui's sources as source text, looking for a
        // UiError flattened into showError's string overload. Same reasoning as the two roots above:
        // :ui-shared IS a dependency, so a changed screen already perturbs this task's runtime
        // classpath today, but that is a side effect, not a declared input. Stating it means the
        // guard keeps re-running the day that stops being true — otherwise the task goes UP-TO-DATE
        // and the guard passes by not running, which is exactly how a source scanner dies quietly.
        // (composeApp's and adminApp's own roots are covered by this module's sources and by the
        // adminApp input declared above.)
        inputs
            .dir(rootProject.layout.projectDirectory.dir("ui/shared/src/commonMain/kotlin"))
            .withPropertyName("sharedUiSourcesScannedByGuardTests")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }
}

// The generated Res package (and the composeResources/<package>/ directory baked into the wasm
// bundle) defaults from the Gradle project name; pin it so renaming the module changes neither.
compose.resources {
    packageOfResClass = "pos.composeapp.generated.resources"
}

compose.desktop {
    application {
        mainClass = "org.example.project.MainKt"

        nativeDistributions {
            // Only include formats for the current OS to avoid cross-platform task conflicts
            val osName = System.getProperty("os.name").lowercase()
            when {
//                osName.contains("mac") -> targetFormats(TargetFormat.Dmg)
                osName.contains("win") -> targetFormats(TargetFormat.Msi)
                else -> targetFormats(TargetFormat.Deb)
            }
            packageName = appPackageName
            packageVersion = appVersion
            description = appDescription
            vendor = appVendor

            // Linux specific settings
            linux {
                val linuxIcon = project.file("src/desktopMain/resources/icon.png")
                if (linuxIcon.exists()) {
                    iconFile.set(linuxIcon)
                }
                packageName = appPackageName
                debMaintainer = email
                menuGroup = "Office"
                appCategory = "Office"
            }

            // Windows specific settings
            windows {
                val windowsIcon = project.file("src/desktopMain/resources/icon.ico")
                if (windowsIcon.exists()) {
                    iconFile.set(windowsIcon)
                }
                menuGroup = appName
                upgradeUuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
            }

            macOS {
                val macIcon = project.file("src/desktopMain/resources/icon.icns")
                if (macIcon.exists()) {
                    iconFile.set(macIcon)
                }
                bundleID = "com.zenbon.pos"
            }

            // Include all required modules for full functionality
            modules(
                "java.instrument",
                "java.management",
                "java.naming",
                "java.prefs",
                "java.sql",
                // dbus-java-core and its unix-socket transport (java-keyring's Linux Secret Service backend). Without them
                // the packaged Linux till hits a linkage error and silently falls back to the key file.
                "jdk.net",
                "jdk.security.auth",
                "jdk.unsupported",
                // Compose already bundles jdk.crypto.ec by default; listed so the ECDSA dependency
                // (Caddy issues P-256 certificates; SunEC lives in this module on JDK 21) is explicit.
                "jdk.crypto.ec",
            )
            // Windows-ROOT keystore (OsTrustStore) — the module exists only in a Windows JDK.
            if (osName.contains("win")) modules("jdk.crypto.mscapi")
        }

        // Release build: ProGuard stays off (see compose-desktop.pro)
        buildTypes.release.proguard {
            isEnabled.set(false)
            configurationFiles.from("compose-desktop.pro")
        }
    }
}

// Post-process the release DEB produced by the Compose MP jpackage task:
//   1. Inject a custom postinst that makes /opt/zenbon-pos group-writable via the "zenbon" group.
//   2. Bundle update-helper.sh into /opt/zenbon-pos/lib/app/ with executable permissions.
// Linux-only: the onlyIf guard skips the entire task on macOS / Windows.
tasks.matching { it.name == "packageReleaseDeb" }.configureEach {
    onlyIf {
        org.gradle.internal.os.OperatingSystem
            .current()
            .isLinux
    }
    // Capture paths at configuration time so doLast doesn't touch `project`,
    // which the configuration cache forbids at execution time.
    val debDirProvider = layout.buildDirectory.dir("compose/binaries/main-release/deb")
    val workDirProvider = layout.buildDirectory.dir("tmp/deb-postinst")
    val postinstSrc = file("src/jpackage-resources/linux/zenbon-pos.postinst")
    val helperSrc = file("src/main/scripts/update-helper.sh")
    doLast {
        val debDir = debDirProvider.get().asFile
        val debFile =
            debDir.listFiles()?.firstOrNull { it.name.endsWith(".deb") }
                ?: return@doLast // nothing to post-process

        val workDir = workDirProvider.get().asFile
        workDir.deleteRecursively()
        workDir.mkdirs()

        fun runCmd(vararg args: String) {
            val result =
                ProcessBuilder(*args)
                    .inheritIO()
                    .start()
                    .waitFor()
            check(result == 0) { "Command failed (exit $result): ${args.joinToString(" ")}" }
        }

        // Unpack
        runCmd("dpkg-deb", "-R", debFile.absolutePath, workDir.absolutePath)

        // Inject postinst
        val postinstDst = File(workDir, "DEBIAN/postinst")
        postinstDst.parentFile.mkdirs()
        postinstSrc.copyTo(postinstDst, overwrite = true)
        postinstDst.setExecutable(true, false)

        // Inject update-helper.sh into /opt/zenbon-pos/lib/app/
        val appLib = File(workDir, "opt/zenbon-pos/lib/app")
        appLib.mkdirs()
        val helperDst = File(appLib, "update-helper.sh")
        helperSrc.copyTo(helperDst, overwrite = true)
        helperDst.setExecutable(true, false)

        // Repack — overwrite the original .deb
        runCmd("dpkg-deb", "-b", workDir.absolutePath, debFile.absolutePath)

        // Clean up
        workDir.deleteRecursively()
    }
}
