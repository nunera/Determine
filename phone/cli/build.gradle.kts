import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { kotlin("jvm"); application }

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("org.determine.cli.MainKt")
    applicationName = "determine-phone"
}

tasks.test { useJUnitPlatform() }
tasks.named<JavaExec>("run") { standardInput = System.`in` }
