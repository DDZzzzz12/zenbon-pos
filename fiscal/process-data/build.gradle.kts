plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

dependencies {
    // `api`, not `implementation`: ProcessDataSpecValidator's public signature exposes
    // ProcessType/BonType from shared, and pos-tse consumes it.
    api(projects.ecrApi)
}

kotlin {
    jvmToolchain(21)
}
