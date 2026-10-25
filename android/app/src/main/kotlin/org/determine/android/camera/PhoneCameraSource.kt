package org.determine.android.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Tier 0 FrameSource (Arch §4.1 "phone camera (Tier 0)", §11.4 demo branch): the phone's back camera, worn on a
 * headband in portrait, instead of the ESP32-CAM. CameraX ImageAnalysis (~640×480, keep-only-latest, so a slow
 * consumer skips frames instead of queueing them) → upright NV21 → JPEG, paced to ~10 fps, delivered with the
 * capture time on the engine clock ([Nv21.capturePhoneUs]).
 *
 * The camera is bound to a private [LifecycleOwner] that lives exactly from [start] to [stop] (the foreground
 * service's engine session), not to any activity, so frames keep flowing with the screen off. Needs the CAMERA
 * permission and the service's `camera` foreground type. If the camera cannot be opened no frames arrive,
 * Describe remains unavailable and [stats] carries the reason; hazard distance still comes only from the board.
 */
class PhoneCameraSource(
    private val context: Context,
    /** Called on the camera analysis thread with an upright JPEG and its capture time (engine µs clock). */
    private val onFrame: (jpeg: ByteArray, w: Int, h: Int, capturePhoneUs: Long) -> Unit,
) {
    data class Stats(val frames: Long, val skipped: Long, val width: Int, val height: Int, val lastEncodeMs: Double?, val error: String?)

    private class SessionLifecycle : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private val main = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "determine-phone-camera").apply { isDaemon = true }
    }
    private val owner = SessionLifecycle()
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    @Volatile private var stopped = false

    @Volatile private var frames = 0L
    @Volatile private var skipped = 0L
    @Volatile private var width = 0
    @Volatile private var height = 0
    @Volatile private var lastEncodeMs: Double? = null
    @Volatile private var error: String? = null

    // Analysis-thread state.
    private var lastAcceptedUs = Long.MIN_VALUE
    private var nv21: ByteArray? = null
    private val jpegOut = ByteArrayOutputStream(64 * 1024)

    fun start() = main.post {
        if (stopped) return@post
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            error = "camera permission not granted"
            Log.e(TAG, "phone camera selected but CAMERA permission is missing")
            return@post
        }
        owner.registry.currentState = Lifecycle.State.STARTED
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (stopped) return@addListener
            try {
                val p = future.get()
                provider = p
                bind(p)
            } catch (e: Exception) {
                error = "camera unavailable: ${e.message ?: e.javaClass.simpleName}"
                Log.e(TAG, "cannot get camera provider", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bind(p: ProcessCameraProvider) {
        val selector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(TARGET_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            .build()
        val a = ImageAnalysis.Builder()
            .setResolutionSelector(selector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            // Rotation relative to the phone's natural (portrait) orientation: the phone is worn upright.
            .setTargetRotation(Surface.ROTATION_0)
            .build()
        a.setAnalyzer(executor, ::analyze)
        analysis = a
        try {
            val camera = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, a)
            camera.cameraInfo.cameraState.observe(owner) { s ->
                val e = s.error
                if (e != null) {
                    error = "camera error ${e.code}"
                    Log.w(TAG, "camera state error ${e.code}", e.cause)
                } else if (s.type == CameraState.Type.OPEN) {
                    error = null
                }
            }
        } catch (e: Exception) {
            error = "cannot open back camera: ${e.message ?: e.javaClass.simpleName}"
            Log.e(TAG, "bindToLifecycle failed", e)
        }
    }

    private fun analyze(image: ImageProxy) {
        image.use { img ->
            val nowRealtimeNs = SystemClock.elapsedRealtimeNanos()
            val captureUs = Nv21.capturePhoneUs(img.imageInfo.timestamp, nowRealtimeNs, System.nanoTime())
            if (lastAcceptedUs != Long.MIN_VALUE && captureUs - lastAcceptedUs < MIN_INTERVAL_US) {
                skipped++
                return
            }
            lastAcceptedUs = captureUs
            val t0 = SystemClock.elapsedRealtimeNanos()
            val rotation = img.imageInfo.rotationDegrees
            val (ow, oh) = Nv21.outputSize(img.width, img.height, rotation)
            val planes = img.planes
            val buf = Nv21.fromYuv420(
                img.width, img.height,
                planes[0].buffer, planes[0].rowStride, planes[0].pixelStride,
                planes[1].buffer, planes[2].buffer, planes[1].rowStride, planes[1].pixelStride,
                rotation, nv21,
            )
            nv21 = buf
            jpegOut.reset()
            if (!YuvImage(buf, ImageFormat.NV21, ow, oh, null).compressToJpeg(Rect(0, 0, ow, oh), JPEG_QUALITY, jpegOut)) {
                skipped++
                return
            }
            val jpeg = jpegOut.toByteArray()
            lastEncodeMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
            width = ow
            height = oh
            frames++
            if (!stopped) onFrame(jpeg, ow, oh, captureUs)
        }
    }

    fun stats(): Stats = Stats(frames, skipped, width, height, lastEncodeMs, error)

    fun stop() {
        stopped = true
        main.post {
            analysis?.let { a ->
                a.clearAnalyzer()
                provider?.unbind(a)
            }
            analysis = null
            if (owner.registry.currentState != Lifecycle.State.INITIALIZED) {
                owner.registry.currentState = Lifecycle.State.DESTROYED
            }
            executor.shutdown()
        }
    }

    companion object {
        private const val TAG = "PhoneCamera"
        private val TARGET_SIZE = Size(640, 480)
        /** ~10 fps (PROTOCOL.md FRAME 10–15 fps); the camera runs faster, frames in between are skipped. */
        private const val MIN_INTERVAL_US = 95_000L
        private const val JPEG_QUALITY = 75
    }
}
