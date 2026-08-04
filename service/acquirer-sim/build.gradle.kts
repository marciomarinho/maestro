plugins {
    id("maestro.spring-service-conventions")
}

// The simulator is a first-class component, not a test fixture (ADR-0011): it is
// what makes every resilience claim in this project demonstrable.
dependencies {
    implementation(project(":lib:lib-domain"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.validation)
}
