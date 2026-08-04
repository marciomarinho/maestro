plugins {
    id("maestro.spring-service-conventions")
    id("maestro.integration-test-conventions")
}

dependencies {
    implementation(project(":lib:lib-domain"))
    implementation(project(":lib:lib-events"))
    implementation(project(":lib:lib-outbox"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.kafka)
    implementation(libs.spring.boot.starter.flyway)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.archunit.junit)
    intTestImplementation(libs.spring.boot.testcontainers)
}
