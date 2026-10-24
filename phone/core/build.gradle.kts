import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { kotlin("jvm") }

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // Golden vectors live in the frozen protocol directory (PROTOCOL.md).
    systemProperty("determine.vectors", rootProject.file("../protocol/vectors/vectors.json").absolutePath)
}
