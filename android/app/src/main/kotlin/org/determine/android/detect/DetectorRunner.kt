package org.determine.android.detect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import org.determine.core.AssembledFrame
import org.determine.core.Detection
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Semantics stage of the pipeline (Arch §7.1): MediaPipe Tasks Vision ObjectDetector in LIVE_STREAM mode
 * with a category allowlist and score threshold, fed with the newest assembled camera JPEG.
 *
 * Detections only label and give bearing; the engine never treats them as the sole trigger for a near-field
 * hazard (Arch §7.1 "Semantics"). If the detector cannot be created (missing model, unsupported device),
 * the system keeps running on ToF with the generic "obstacle" label (Arch §11.3 abort branch), and
 * [available] is false so the diagnostics screen shows it.
 */
class DetectorRunner(
    context: Context,
    private val clockUs: () -> Long,
    /** Called on a MediaPipe thread with the frame's identity and its normalised detections. */
    private val onDetections: (frameId: Long, capturePhoneUs: Long, detections: List<Detection>, nowPhoneUs: Long) -> Unit,
) {
    data class Stats(
        val available: Boolean,
        val error: String?,
        val submitted: Long,
        val skippedBusy: Long,
        val skippedStale: Long,
        val abandoned: Long,
        val decodeFailures: Long,
        val lastInferenceMs: Double?,
    )

    private val lock = Any()
    private val pacer = FramePacer()
    private val decodeExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "determine-detect-decode").apply { isDaemon = true }
    }
    private var detector: ObjectDetector? = null
    private var createError: String? = null
    private var inflightSubmitUs = 0L
    private var inflightTimestampMs = Long.MIN_VALUE
    private var lastInferenceMs: Double? = null
    private var decodeFailures = 0L
    /** One bitmap recycled between frames; only reused once MediaPipe has returned the previous input. */
    private var spareBitmap: Bitmap? = null

    init {
        try {
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(MODEL_ASSET)
                        .setDelegate(Delegate.CPU)
                        .build(),
                )
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setScoreThreshold(SCORE_THRESHOLD)
                .setMaxResults(MAX_RESULTS)
                .setCategoryAllowlist(CATEGORY_ALLOWLIST)
                .setResultListener(::onResult)
                .setErrorListener { e -> onError(e) }
                .build()
            detector = ObjectDetector.createFromOptions(context, options)
        } catch (e: Exception) {
            createError = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "ObjectDetector unavailable; running ToF-only", e)
        }
    }

    val available: Boolean get() = detector != null

    /**
     * Offer the engine's newest frame. Cheap; called every engine tick. Skips (never queues) when the
     * detector is busy or the frame is stale or already handled (see [FramePacer]).
     */
    fun offer(frame: AssembledFrame, nowUs: Long) {
        val d = detector ?: return
        val submission = synchronized(lock) {
            pacer.offer(frame.frameId, frame.captureBoardUs, frame.capturePhoneUs, nowUs)?.also {
                inflightSubmitUs = nowUs
                inflightTimestampMs = it.timestampMs
            }
        } ?: return
        decodeExecutor.execute { decodeAndDetect(d, frame, submission.timestampMs) }
    }

    private fun decodeAndDetect(d: ObjectDetector, frame: AssembledFrame, timestampMs: Long) {
        val bitmap = decode(frame.jpeg)
        if (bitmap == null) {
            synchronized(lock) {
                decodeFailures++
                pacer.onError(timestampMs)
            }
            return
        }
        try {
            d.detectAsync(BitmapImageBuilder(bitmap).build(), timestampMs)
        } catch (e: RuntimeException) {
            Log.w(TAG, "detectAsync failed", e)
            synchronized(lock) { pacer.onError(timestampMs) }
        }
    }

    private fun decode(jpeg: ByteArray): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
            inBitmap = synchronized(lock) { spareBitmap.also { spareBitmap = null } }
        }
        return try {
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
        } catch (e: IllegalArgumentException) {
            // inBitmap could not be reused (frame size changed); decode into a fresh bitmap.
            opts.inBitmap = null
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
        }
    }

    private fun onResult(result: ObjectDetectorResult, input: MPImage) {
        val now = clockUs()
        val submission = synchronized(lock) {
            val s = pacer.onResult(result.timestampMs()) ?: return // abandoned/unknown: discard
            lastInferenceMs = (now - inflightSubmitUs) / 1000.0
            // MediaPipe has finished with this input; its bitmap can back the next decode.
            spareBitmap = extractBitmap(input)
            s
        }
        val w = input.width.toFloat()
        val h = input.height.toFloat()
        if (w <= 0f || h <= 0f) return
        val detections = result.detections().mapNotNull { det ->
            val category = det.categories().maxByOrNull { it.score() } ?: return@mapNotNull null
            val box = det.boundingBox()
            Detection(
                label = category.categoryName(),
                score = category.score(),
                left = (box.left / w).coerceIn(0f, 1f),
                top = (box.top / h).coerceIn(0f, 1f),
                right = (box.right / w).coerceIn(0f, 1f),
                bottom = (box.bottom / h).coerceIn(0f, 1f),
            )
        }
        onDetections(submission.frameId, submission.capturePhoneUs, detections, now)
    }

    private fun extractBitmap(image: MPImage): Bitmap? = try {
        com.google.mediapipe.framework.image.BitmapExtractor.extract(image)
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun onError(e: RuntimeException) {
        Log.w(TAG, "detector error", e)
        synchronized(lock) { pacer.onError(inflightTimestampMs) }
    }

    fun stats(): Stats = synchronized(lock) {
        Stats(
            available = detector != null,
            error = createError,
            submitted = pacer.submitted,
            skippedBusy = pacer.skippedBusy,
            skippedStale = pacer.skippedStale,
            abandoned = pacer.abandoned,
            decodeFailures = decodeFailures,
            lastInferenceMs = lastInferenceMs,
        )
    }

    fun close() {
        decodeExecutor.shutdownNow()
        detector?.close()
        detector = null
    }

    companion object {
        private const val TAG = "DetectorRunner"
        const val MODEL_ASSET = "efficientdet_lite0.tflite"
        const val SCORE_THRESHOLD = 0.5f
        const val MAX_RESULTS = 5

        /**
         * COCO classes that matter at chest/head height on a walking route (Arch §7.1). COCO has no "door"
         * class, so doors are reported by the ToF corridor as a generic obstacle.
         */
        val CATEGORY_ALLOWLIST = listOf(
            "person", "bicycle", "car", "motorcycle", "bus", "truck",
            "traffic light", "fire hydrant", "stop sign", "parking meter",
            "bench", "chair", "couch", "potted plant", "dining table",
            "dog", "suitcase", "refrigerator", "tv",
        )

    }
}
