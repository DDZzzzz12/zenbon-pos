import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

val appVersion = property("app.version") as String
val appVersionCode = (property("app.versionCode") as String).toInt()
val appName = property("app.name") as String

android {
    namespace = "org.example.project"
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()

    buildFeatures {
        resValues = true
    }

    defaultConfig {
        applicationId = "org.example.project"
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.android.targetSdk
                .get()
                .toInt()
        versionCode = appVersionCode
        versionName = appVersion
        resValue("string", "app_name", appName)
    }

    // Legacy location "composeApp/keystore.properties" is accepted as a fallback so a pre-restructure
    // checkout keeps signing without a manual move. Remove it after the first release cut from the new layout.
    val keystorePropertiesFile =
        rootProject.file("ui/till/keystore.properties").takeIf { it.exists() }
            ?: rootProject.file("composeApp/keystore.properties")
    val keystoreProperties = Properties()
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
    }

    if (keystoreProperties.isNotEmpty()) {
        signingConfigs {
            create("release") {
                // Resolved against the repo root. The legacy value "../release-keystore.jks" was written
                // when this module sat at <root>/androidApp, so one leading "../" is dropped.
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile").removePrefix("../"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreProperties.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    // The KMP compose module — exposes all shared UI + Android platform code
    implementation(projects.uiTill)

    // :ui-till depends on the area api modules with `implementation`, so their types are NOT on this
    // module's compile classpath transitively. PosApplication calls DeviceInfoProvider, which
    // lives in :platform-api, so declare the modules directly.
    implementation(projects.platformApi)
    implementation(projects.ecrApi)
    implementation(projects.fiscalApi)
    implementation(projects.backofficeApi)

    // Same story for :ui-shared — MainActivity calls FileSaver.initialize(), and FileSaver's
    // Android actual lives in :ui-shared, which composeApp also depends on with `implementation`.
    implementation(projects.uiShared)

    // Android entry-point dependencies (needed directly by MainActivity/PosApplication)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.koin.android)
    implementation(libs.kermit)
}
