plugins {
    id("maestro.library-conventions")
}

dependencies {
    api(project(":lib:lib-events"))
    api(libs.spring.boot.starter.jdbc)
    api(libs.spring.boot.starter.kafka)
}
