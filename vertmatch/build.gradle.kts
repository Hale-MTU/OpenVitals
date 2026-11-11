// The VertMatch partner API client (https://vertmatch.run/developers).
//
// DORMANT BY DESIGN. Nothing in :app depends on this module, because OpenVitals
// ships without the INTERNET permission and this code needs a socket. It is a
// plain Kotlin/JVM library, so it builds and tests without the Android SDK, and
// it stays inert until someone decides how the app gets network access. See
// vertmatch/README.md and `DormantBoundaryTest`.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // Public API surface stays deliberate: nothing is exported by accident.
        explicitApi()
    }
}

dependencies {
    // Only the JSON tree API is used: no compiler plugin, no generated serializers.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "512m"
}
