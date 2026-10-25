plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
}

group = "org.determine"
version = "0.1.0"

kotlin {
    jvmToolchain(17)
}

// The Android app's screens, runtime and engine host, compiled unchanged for desktop (Compose Multiplatform).
sourceSets {
    main { kotlin.srcDir("../app-shared/src/main/kotlin") }
    test { kotlin.srcDir("../app-shared/src/test/kotlin") }
}

dependencies {
    implementation("org.determine:core")
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
    // Built into Android; the shared Describe client uses it.
    implementation("org.json:json:20240303")

    testImplementation("junit:junit:4.13.2")
}

compose.desktop {
    application {
        mainClass = "org.determine.desktop.MainKt"
        // `./gradlew run --args="--sim approach_centre --start"`; see desktop/README.md.
    }
}
