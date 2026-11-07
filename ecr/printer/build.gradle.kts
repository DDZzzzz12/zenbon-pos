plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

dependencies {
    // Server-common utilities (dbQuery, formatters, error handling)
    implementation(projects.platformServerCommon)
    implementation(projects.ecrReceipt)

    // The printer core (ESC/POS emitter, enums, utils, BaseDocumentFormatter); re-exported so formatter subclasses elsewhere still compile
    api(projects.ecrPrinterCore)

    // The ecr data tier (tables, entities, repositories; re-exports :ecr-api, :platform-data and Exposed)
    implementation(projects.ecrData)

    // IPP Printer Discovery & Communication
    implementation(libs.ipp.client)

    // mDNS/DNS-SD Discovery (cross-platform IPP printer discovery without CUPS)
    implementation(libs.jmdns)

    // Ktor (for route definitions)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverAuth)
    implementation(libs.ktor.serverAuthJwt)
    implementation(libs.ktor.serverContentNegotiation)
    implementation(libs.ktor.serializationKotlinxJson)

    // Koin
    implementation(libs.koin.core)
    implementation(libs.koin.ktor)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)

    // DateTime
    implementation(libs.kotlinx.datetime)

    // Logging
    implementation(libs.logback)

    // Tests
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}
