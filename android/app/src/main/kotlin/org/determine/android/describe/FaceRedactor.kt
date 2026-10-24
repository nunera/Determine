package org.determine.android.describe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import org.determine.core.AssembledFrame
import org.determine.core.describe.Redactor
import java.io.ByteArrayOutputStream

/**
 * Arch §7.3 / invariant 4: faces are blurred on the phone before any Describe upload. MediaPipe FaceDetector
 * (BlazeFace short-range, IMAGE mode) finds faces; each box, grown by [MARGIN] on every side, is pixelated so
 * coarsely ([BLOCK_PX]) that no facial detail survives, then the frame is re-encoded as JPEG.
 *
 * Returns null — and the controller then uploads nothing — if the model is missing, the JPEG cannot be decoded,
 * detection throws, or encoding fails. Redaction is imperfect (small, distant or occluded faces can be missed);
 * the Settings screen says so before the user opts in.
 *
 * Called synchronously from DescribeController.request() under the engine lock, once per user long-press.
 */
class FaceRedactor(context: Context) : Redactor {
    private val detector: FaceDetector? = try {
        FaceDetector.createFromOptions(
            context,
            FaceDetector.FaceDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
                .setRunningMode(RunningMode.IMAGE)
                .setMinDetectionConfidence(MIN_CONFIDENCE)
                .build(),
        )
    } catch (e: RuntimeException) {
        Log.e(TAG, "face detector unavailable; Describe uploads are disabled", e)
        null
    } catch (e: LinkageError) {
        // No MediaPipe native library for this ABI: no redaction, therefore no uploads.
        Log.e(TAG, "face detector native library missing; Describe uploads are disabled", e)
        null
    }

    val available: Boolean get() = detector != null

    @Volatile var lastFaceCount: Int = 0
        private set

    override fun redact(frame: AssembledFrame): ByteArray? {
        val det = detector ?: return null
        return try {
            val decoded = BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size) ?: return null
            val bmp = decoded.copy(Bitmap.Config.ARGB_8888, true)
            decoded.recycle()
            val faces = det.detect(BitmapImageBuilder(bmp).build()).detections()
            val canvas = Canvas(bmp)
            for (f in faces) {
                val b = f.boundingBox()
                val mx = b.width() * MARGIN
                val my = b.height() * MARGIN
                val r = Rect(
                    (b.left - mx).toInt().coerceAtLeast(0),
                    (b.top - my).toInt().coerceAtLeast(0),
                    (b.right + mx).toInt().coerceAtMost(bmp.width),
                    (b.bottom + my).toInt().coerceAtMost(bmp.height),
                )
                if (r.width() > 0 && r.height() > 0) pixelate(bmp, canvas, r)
            }
            lastFaceCount = faces.size
            val out = ByteArrayOutputStream(frame.jpeg.size)
            val ok = bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            bmp.recycle()
            if (ok) out.toByteArray() else null
        } catch (e: RuntimeException) {
            Log.w(TAG, "redaction failed; not uploading", e)
            null
        }
    }

    /** Downscale the region to blocks of [BLOCK_PX] and draw it back without filtering. */
    private fun pixelate(bmp: Bitmap, canvas: Canvas, r: Rect) {
        val region = Bitmap.createBitmap(bmp, r.left, r.top, r.width(), r.height())
        val small = Bitmap.createScaledBitmap(
            region,
            (r.width() / BLOCK_PX).coerceAtLeast(1),
            (r.height() / BLOCK_PX).coerceAtLeast(1),
            true,
        )
        val blocky = Bitmap.createScaledBitmap(small, r.width(), r.height(), false)
        canvas.drawBitmap(blocky, r.left.toFloat(), r.top.toFloat(), null)
        region.recycle(); small.recycle(); blocky.recycle()
    }

    fun close() {
        detector?.close()
    }

    private companion object {
        const val TAG = "FaceRedactor"
        const val MODEL = "blaze_face_short_range.tflite"
        /** Low threshold: a false blur costs little, a missed face is a privacy failure. */
        const val MIN_CONFIDENCE = 0.3f
        const val MARGIN = 0.25f
        const val BLOCK_PX = 24
        const val JPEG_QUALITY = 80
    }
}
