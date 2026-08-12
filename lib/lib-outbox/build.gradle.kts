plugins {
    id("maestro.library-conventions")
}

dependencies {
    api(project(":lib:lib-events"))
    api(libs.spring.boot.starter.jdbc)
    api(libs.spring.boot.starter.kafka)
    // API only. The writer records the current trace context into the outbox row; the
    // OTel bridge that makes these types live comes from the service, not from here.
    implementation(libs.micrometer.tracing)
    // For the pending/oldest-age gauges and their names.
    implementation(project(":lib:lib-observability"))
    implementation(libs.micrometer.core)
}
