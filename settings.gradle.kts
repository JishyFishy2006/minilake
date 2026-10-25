plugins {
    // Lets Gradle download Java 21 automatically if it's missing
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "minilake"

include(
    "minilake-core",
    "minilake-storage",
    "minilake-parquet",
    "minilake-harness",
    "minilake-cli",
    "minilake-bench",
)
