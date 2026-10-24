package org.determine.android.describe

import android.util.Log
import org.determine.core.describe.DescribeRequest
import org.determine.core.describe.DescribeResult
import org.determine.core.describe.DescribeTransport
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** Where the describe-api lives (cloud/API.md §1): base URL without the `/v1/describe` path, and the bearer key. */
data class DescribeEndpoint(val baseUrl: String, val apiKey: String)

/**
 * Phone → describe-api client (cloud/API.md §1, contract v1): `POST {baseUrl}/v1/describe` with a bearer key and the
 * already-redacted JPEG, 8 s end to end. Every HTTP/network error maps to [DescribeResult.Failed] with the API's
 * error code and retryable flag; nothing is retried here (Describe is user-initiated: the user asks again).
 *
 * [send] never blocks the caller (it runs on the engine thread): the request runs on a background executor, a
 * watchdog disconnects it at the deadline, and [cancel] disconnects it from the scheduler thread. A cancelled request
 * never calls back. The image is held only for the duration of the request; nothing is stored or logged.
 */
class HttpDescribeTransport(
    private val endpoint: () -> DescribeEndpoint?,
    /** Every delivered result with the phone-measured round trip, for the judge screen; any thread. */
    private val onFinished: (DescribeResult, Long) -> Unit = { _, _ -> },
    private val totalTimeoutMs: Long = DescribeHttp.TOTAL_TIMEOUT_MS,
) : DescribeTransport {
    private class Call {
        @Volatile var conn: HttpURLConnection? = null
        @Volatile var cancelled = false
        @Volatile var timedOut = false
        @Volatile var future: Future<*>? = null
    }

    private val calls = ConcurrentHashMap<String, Call>()
    private val workers: ExecutorService = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "determine-describe-http").apply { isDaemon = true }
    }
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "determine-describe-timer").apply { isDaemon = true }
    }

    override fun send(req: DescribeRequest, onResult: (DescribeResult) -> Unit) {
        val call = Call()
        calls[req.requestId] = call
        call.future = workers.submit {
            val t0 = System.nanoTime()
            val result = execute(req, call)
            calls.remove(req.requestId, call)
            if (!call.cancelled) {
                onResult(result)
                onFinished(result, (System.nanoTime() - t0) / 1_000_000)
            }
        }
    }

    override fun cancel(requestId: String) {
        val call = calls.remove(requestId) ?: return
        call.cancelled = true
        // disconnect() may touch the socket: never on the caller's (engine) thread.
        scheduler.execute { call.conn?.disconnect() }
        call.future?.cancel(false)
    }

    private fun execute(req: DescribeRequest, call: Call): DescribeResult {
        val ep = endpoint() ?: return DescribeResult.Failed(req.requestId, "not_configured", retryable = false)
        DescribeHttp.validate(req)?.let { return it }
        val url = DescribeHttp.describeUrl(ep.baseUrl) ?: return DescribeResult.Failed(req.requestId, "bad_endpoint", retryable = false)
        val body = DescribeHttp.requestBody(req).toByteArray(Charsets.UTF_8)
        val watchdog = scheduler.schedule({
            call.timedOut = true
            call.conn?.disconnect()
        }, totalTimeoutMs, TimeUnit.MILLISECONDS)
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            call.conn = conn
            if (call.cancelled) return DescribeResult.Failed(req.requestId, "cancelled", retryable = false)
            conn.connectTimeout = totalTimeoutMs.toInt()
            conn.readTimeout = totalTimeoutMs.toInt()
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.useCaches = false
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Authorization", "Bearer ${ep.apiKey}")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val status = conn.responseCode
            val text = (if (status >= 400) conn.errorStream else conn.inputStream)?.use { readCapped(it) }
            return if (call.timedOut) DescribeHttp.timeout(req.requestId) else DescribeHttp.mapResponse(req.requestId, status, text)
        } catch (e: Exception) {
            // IOException for network/HTTP; RuntimeException for platform quirks (e.g. a malformed host).
            if (!call.cancelled) Log.w(TAG, "describe ${req.requestId} failed: ${e.javaClass.simpleName}")
            return DescribeHttp.mapException(req.requestId, e, call.timedOut)
        } finally {
            watchdog.cancel(false)
            call.conn?.disconnect()
        }
    }

    private fun readCapped(input: InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < DescribeHttp.MAX_RESPONSE_BYTES) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, minOf(n, DescribeHttp.MAX_RESPONSE_BYTES - out.size()))
        }
        return out.toString("UTF-8")
    }

    fun close() {
        calls.values.forEach { it.cancelled = true; it.conn?.disconnect() }
        calls.clear()
        workers.shutdownNow()
        scheduler.shutdownNow()
    }

    private companion object {
        const val TAG = "DescribeHttp"
    }
}

/** Pure request building and response/error mapping for cloud/API.md §1 (unit-tested). */
internal object DescribeHttp {
    const val TOTAL_TIMEOUT_MS = 8_000L
    const val MAX_JPEG_BYTES = 512 * 1024
    const val MAX_RESPONSE_BYTES = 64 * 1024
    const val LOCALE = "en"

    fun describeUrl(baseUrl: String): String? {
        val b = baseUrl.trim().trimEnd('/')
        if (!(b.startsWith("https://") || b.startsWith("http://")) || b.length <= "https://".length) return null
        return "$b/v1/describe"
    }

    /** Local checks of the API's request rules, so an invalid image is never uploaded. */
    fun validate(req: DescribeRequest): DescribeResult.Failed? {
        val j = req.jpeg
        if (j.size > MAX_JPEG_BYTES) return DescribeResult.Failed(req.requestId, "too_large", retryable = false)
        if (j.size < 2 || j[0] != 0xFF.toByte() || j[1] != 0xD8.toByte()) return DescribeResult.Failed(req.requestId, "bad_request", retryable = false)
        if (req.width !in 16..2048 || req.height !in 16..2048) return DescribeResult.Failed(req.requestId, "bad_request", retryable = false)
        return null
    }

    /** `redacted` is always true: the controller only sends images the [FaceRedactor] produced. */
    fun requestBody(req: DescribeRequest): String = JSONObject()
        .put("request_id", req.requestId)
        .put("image_jpeg_b64", Base64.getEncoder().encodeToString(req.jpeg))
        .put("width", req.width)
        .put("height", req.height)
        .put("redacted", true)
        .put("locale", LOCALE)
        .toString()

    fun mapResponse(requestId: String, status: Int, body: String?): DescribeResult {
        if (status == 200) return parseOk(requestId, body)
        val json = body?.let { runCatching { JSONObject(it) }.getOrNull() }
        val code = json?.optString("error")?.takeIf { it.isNotBlank() } ?: defaultCode(status)
        val retryable = if (json != null && json.has("retryable") && json.opt("retryable") is Boolean) {
            json.getBoolean("retryable")
        } else {
            defaultRetryable(status)
        }
        return DescribeResult.Failed(requestId, code, retryable)
    }

    private fun parseOk(requestId: String, body: String?): DescribeResult {
        val json = try {
            JSONObject(body ?: return badResponse(requestId))
        } catch (_: JSONException) {
            return badResponse(requestId)
        }
        val echoed = json.optString("request_id", requestId)
        if (echoed != requestId) return badResponse(requestId)
        val text = json.opt("text") as? String
        if (text.isNullOrBlank()) return badResponse(requestId)
        val timings = json.optJSONObject("timings_ms")
        return DescribeResult.Ok(
            requestId = requestId,
            text = text.trim(),
            backend = json.optString("backend").ifBlank { "unknown" },
            silicon = json.optString("silicon").ifBlank { "unspecified" },
            inferenceMs = timings?.optLongOrNull("inference"),
            totalMs = timings?.optLongOrNull("total"),
        )
    }

    private fun JSONObject.optLongOrNull(key: String): Long? = (opt(key) as? Number)?.toLong()

    private fun badResponse(requestId: String) = DescribeResult.Failed(requestId, "bad_response", retryable = false)

    fun timeout(requestId: String) = DescribeResult.Failed(requestId, "timeout", retryable = true)

    fun mapException(requestId: String, e: Exception, timedOut: Boolean): DescribeResult.Failed = when {
        timedOut || e is SocketTimeoutException -> timeout(requestId)
        // A certificate or protocol mismatch will not fix itself on retry.
        e is SSLException -> DescribeResult.Failed(requestId, "tls", retryable = false)
        else -> DescribeResult.Failed(requestId, "network", retryable = true)
    }

    /** cloud/API.md §1 error codes by status, for bodies that carry none (proxies, gateways). */
    fun defaultCode(status: Int): String = when (status) {
        400 -> "bad_request"
        401, 403 -> "unauthorized"
        413 -> "too_large"
        502 -> "inference_failed"
        503 -> "unavailable"
        504 -> "timeout"
        else -> "http_$status"
    }

    fun defaultRetryable(status: Int): Boolean = status == 408 || status == 429 || status in 500..599
}
