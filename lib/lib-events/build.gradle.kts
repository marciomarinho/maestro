plugins {
    id("maestro.library-conventions")
}

dependencies {
    api(project(":lib:lib-domain"))
    api(libs.jackson.databind)
}
