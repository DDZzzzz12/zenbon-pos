plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

group = "org.example.project"
version = "1.0.0"

sourceSets {
    main {
        java.setSrcDirs(emptyList<String>())
    }
}

repositories {
    mavenCentral()
}

// Proprietary Swissbit SDK jar. Present in the private source tree only; the
// open-source distribution expects the operator to supply it (see README).
val wormApiJar = file("lib/WormAPI.jar")

dependencies {
    // Logging
    implementation(libs.logback)

    // Fiscal and platform API types (was :shared, which is now the accounting contract blob)
    implementation(projects.fiscalApi)
    implementation(projects.platformApi)

    // Server-common for PosException (TSE migration Task 5 — operations layer throws PosException)
    implementation(projects.platformServerCommon)
    implementation(projects.fiscalProcessData)

    // WormAPI jar (proprietary Swissbit SDK) — compileOnly on purpose.
    //
    // compileOnly keeps it out of runtimeElements, so it is NOT shaded into any
    // consumer's fat JAR (notably :pos-server's shadowJar). At runtime it is
    // resolved from the deployment's native/ folder via the Class-Path manifest
    // entry; dev/test runs put it on the classpath explicitly below.
    //
    // Do not change this to implementation/runtimeOnly — both re-export the SDK
    // and would put Swissbit classes back inside the published pos-server.jar.
    compileOnly(files(wormApiJar))

    // Testing
    testImplementation(files(wormApiJar))
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("io.mockk:mockk:1.13.9")
}

// Virtual WormAPI test double (src/virtualWorm): a JNI-free reimplementation of the
// com.secureflashcard.wormapi classes backed by an in-memory device. Swapped in for
// lib/WormAPI.jar on the TEST runtime classpath only — never packaged into jars,
// fat JARs, or distributions.
val virtualWorm: SourceSet by sourceSets.creating

// Determine native library subdirectory based on OS and architecture
val nativeLibDir = when {
    System.getProperty("os.name").lowercase().contains("win") ->
        if (System.getProperty("os.arch").contains("64")) "windows64" else "windows32"
    else ->
        if (System.getProperty("os.arch").contains("64") || System.getProperty("os.arch").contains("amd64")) "linux64" else "linux32"
}

// Absolute path for development (Gradle run tasks, tests)
val devNativeLibPath = file("src/main/resources/native/$nativeLibDir").absolutePath

// Configure tests to find native libraries and use JUnit 5.
// With TSE_MOUNT_POINT set (real-hardware hosts), tests run against the real SWIG/JNI
// WormAPI.jar + native libs — the same variable already gates the
// @EnabledIfEnvironmentVariable hardware tests. Without it, the virtual WormAPI
// (virtualWorm source set) replaces the jar so the suite runs on any OS, including
// macOS, for which no native WormAPI build exists.
tasks.test {
    useJUnitPlatform()
    jvmArgs("-Djava.library.path=$devNativeLibPath")
    environment("LD_LIBRARY_PATH", devNativeLibPath)
    if (!providers.environmentVariable("TSE_MOUNT_POINT").isPresent) {
        classpath = files(virtualWorm.output) + classpath.filter { it.name != "WormAPI.jar" }
    }
}

// Exposes the virtualWorm test double to other projects (:pos-server:runVirtual) without
// putting it on any runtime classpath. Test-only: it is NOT wired into runtimeElements, so
// virtual classes still cannot reach a published artifact.
val virtualWormElements: Configuration by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}

// classesDirs holds one directory per language (java + kotlin), so each is added
// separately — .singleFile would throw.
artifacts {
    virtualWorm.output.classesDirs.forEach { classesDir ->
        add(virtualWormElements.name, classesDir) {
            builtBy(tasks.named("virtualWormClasses"))
        }
    }
}
