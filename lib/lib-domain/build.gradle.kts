plugins {
    id("maestro.library-conventions")
}

// lib-domain deliberately has no dependencies at all — no Spring, no Jackson, no
// JDBC. It holds the money type, the identifiers and the state machines, and an
// ArchUnit rule fails the build if a framework ever leaks in (ADR-0001).
dependencies {
}
