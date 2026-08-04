plugins {
    id("maestro.library-conventions")
}

// Shared Testcontainers fixtures. Containers are started once per JVM and reused
// across every integration test class, so a full suite pays the startup cost once.
dependencies {
    api(libs.bundles.testcontainers)
    api(libs.junit.jupiter)
}
