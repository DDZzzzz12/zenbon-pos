plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

dependencies {
    // Platform api tier (error envelope, Capability), not the residual :shared blob — server-common is
    // platform-only (spec §14) and must compile without :shared.
    // `api`, not `implementation`: server-common's own public API exposes platform-api types
    // (`PosException.code: PosErrorCode`, `ApplicationCall.heldCapabilities(): Set<Capability>`).
    api(projects.platformApi)

    // Exposed ORM (for DatabaseTransactionManager / dbQuery)
    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.kotlinDatetime)
    implementation(libs.exposed.json)

    // PostgreSQL (for TransactionRetry PSQLException handling)
    implementation(libs.postgresql)

    // DateTime
    implementation(libs.kotlinx.datetime)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Ktor (config YAML, server core, auth for route utilities)
    implementation(libs.ktor.serverConfigYaml)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverAuth)
    implementation(libs.ktor.serverAuthJwt)

    // Koin
    implementation(libs.koin.core)
    implementation(libs.koin.ktor)

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
