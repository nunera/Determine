import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.determine.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.determine.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        // MediaPipe memory-maps the model; it must be stored uncompressed in the APK.
        noCompress += "tflite"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    // UI, runtime and engine host shared with the desktop test app (../desktop): compiled into both.
    sourceSets {
        getByName("main").kotlin.srcDir("../../app-shared/src/main/kotlin")
        getByName("test").kotlin.srcDir("../../app-shared/src/test/kotlin")
    }
}

/**
 * Downloads a MediaPipe model into a generated assets directory (never committed). The SHA-256 pins the exact
 * file so a changed upstream model cannot silently alter behaviour. Only the face model for Describe redaction
 * remains: hazard distance never depends on any image model.
 */
abstract class DownloadModel : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:Input abstract val fileName: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun download() {
        val out = outputDir.get().asFile.resolve(fileName.get())
        if (out.isFile && sha256Of(out.readBytes()) == sha256.get()) return
        out.parentFile.mkdirs()
        val bytes = URI(url.get()).toURL().openStream().use { it.readBytes() }
        val actual = sha256Of(bytes)
        check(actual == sha256.get()) {
            "Model hash mismatch: expected ${sha256.get()}, got $actual from ${url.get()}"
        }
        out.writeBytes(bytes)
    }

    private fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

// MediaPipe BlazeFace short-range (the face model MediaPipe Tasks FaceDetector supports), used to blur faces
// before a Describe upload. Faces far from the camera may be missed: redaction is imperfect (Arch §7.3).
val downloadFaceModel = tasks.register<DownloadModel>("downloadFaceModel") {
    url.set("https://storage.googleapis.com/mediapipe-models/face_detector/blaze_face_short_range/float16/1/blaze_face_short_range.tflite")
    sha256.set("b4578f35940bf5a1a655214a1cce5cab13eba73c1297cd78e1a04c2380b0152f")
    fileName.set("blaze_face_short_range.tflite")
    outputDir.set(layout.buildDirectory.dir("generated/faceModel/assets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(downloadFaceModel, DownloadModel::outputDir)
    }
}

dependencies {
    implementation("org.determine:core")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("com.google.mediapipe:tasks-vision:0.10.20")

    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    testImplementation("junit:junit:4.13.2")
}
