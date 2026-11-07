plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

dependencies {
    // The accounting area's api tier (DTOs/enums); re-exported so consumers of :accounting-document see its types.
    api(projects.accountingApi)

    // The accounting data tier (tables/repositories/models its services use); re-exported like the api tier.
    api(projects.accountingData)
    implementation(projects.fiscalData)

    // Server-common utilities (dbQuery, formatters, error handling)
    implementation(projects.platformServerCommon)
    implementation(projects.ecrReceipt)

    // E-invoice parsing core (format-agnostic EInvoice model). Leaf module — the dependency runs
    // this way only; :accounting-einvoice must never depend back on a feature module.
    implementation(projects.accountingEinvoice)

    // Bank-statement parsing core (format-agnostic BankStatement model). Leaf module — the
    // dependency runs this way only; :accounting-banking must never depend back on a feature module.
    implementation(projects.accountingBanking)

    // Document Processing - PDF Generation using OpenPDF
    implementation(libs.openpdf)

    // PDF unlocking on upload (PdfUnlocker). Same version the :accounting-einvoice parser uses.
    implementation(libs.pdfbox)

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
