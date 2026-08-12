plugins {
    id("maestro.spring-service-conventions")
    id("maestro.integration-test-conventions")
}

dependencies {
    implementation(project(":lib:lib-domain"))
    implementation(project(":lib:lib-events"))
    // Not for an outbox — the ledger publishes nothing. This brings the shared
    // messaging wiring: declared topics and dead-letter handling for the listener.
    implementation(project(":lib:lib-outbox"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.kafka)
    implementation(libs.spring.boot.starter.flyway)
    // The starter brings Flyway's core only; without the dialect module it refuses to
    // recognise PostgreSQL at all and the service dies on startup.
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.archunit.junit)
    intTestImplementation(libs.spring.boot.testcontainers)
}
