plugins {
    id("maestro.library-conventions")
}

dependencies {
    // Brings micrometer-core and the Boot auto-configuration machinery; the services
    // already depend on actuator, so this adds no weight they do not carry.
    api(libs.spring.boot.starter.actuator)
    // For the W3C propagator contributed when span export is off — see
    // ObservabilityAutoConfiguration for why Boot does not do this itself.
    implementation(libs.opentelemetry.api)
    // For the observation predicate that recognises HTTP server contexts. Every
    // service is a web service, so this adds nothing they do not already carry.
    implementation(libs.spring.web)
    compileOnly(libs.jakarta.servlet) // provided by the embedded container at runtime
}
