plugins {
    alias(libs.plugins.kotlinJvm)
}

dependencies {
    implementation(projects.platformSecureXml)
    implementation(projects.accountingApi)
    implementation(libs.kotlinx.datetime)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}
