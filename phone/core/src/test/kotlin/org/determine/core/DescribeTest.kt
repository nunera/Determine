package org.determine.core

import org.determine.core.describe.DescribeConfig
import org.determine.core.describe.DescribeController
import org.determine.core.describe.DescribeOutcome
import org.determine.core.describe.DescribeRequest
import org.determine.core.describe.DescribeResult
import org.determine.core.describe.DescribeSpeech
import org.determine.core.describe.DescribeState
import org.determine.core.describe.DescribeTransport
import org.determine.core.describe.Redactor
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FakeTransport : DescribeTransport {
    val sent = mutableListOf<DescribeRequest>()
    val callbacks = mutableMapOf<String, (DescribeResult) -> Unit>()
    val cancelled = mutableListOf<String>()
    override fun send(req: DescribeRequest, onResult: (DescribeResult) -> Unit) { sent += req; callbacks[req.requestId] = onResult }
    override fun cancel(requestId: String) { cancelled += requestId }

    /** The HTTP client answers on its own thread. */
    fun answer(r: DescribeResult) {
        val t = Thread { callbacks.getValue(r.requestId)(r) }
        t.start(); t.join()
    }
}

class FakeSpeech : DescribeSpeech {
    val descriptions = mutableListOf<String>()
    val said = mutableListOf<String>()
    var stops = 0
    override fun speakDescription(text: String) { descriptions += text }
    override fun stopDescription() { stops++ }
    override fun say(text: String) { said += text }
}

class DescribeTest {
    private val redacted = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)

    private inner class Setup(config: DescribeConfig = DescribeConfig(), redact: (AssembledFrame) -> ByteArray? = { redacted }) {
        val rig = FallbackRig(EngineConfig(frameSource = FrameSourceKind.PHONE_CAMERA)).ready()
        val transport = FakeTransport()
        val speech = FakeSpeech()
        val redactorCalls = mutableListOf<AssembledFrame>()
        val c = DescribeController(rig.engine, transport, Redactor { f -> redactorCalls += f; redact(f) }, speech, config)
            .also { it.enabled = true }

        fun start(): String {
            assertEquals(DescribeOutcome.STARTED, c.request(rig.now))
            assertEquals(DescribeState.WAITING, c.state)
            return transport.sent.last().requestId
        }

        fun ok(id: String, text: String) = DescribeResult.Ok(id, text, "inf2", "Inferentia2", 812, 855)
    }

    private fun alert(band: Band) = Alert(band, 1100)

    @Test
    fun `answer is redacted upload, prefixed and spoken`() {
        val s = Setup()
        val id = s.start()
        val req = s.transport.sent.single()
        assertArrayEquals(redacted, req.jpeg, "only the redacted JPEG is uploaded")
        assertEquals(640 to 480, req.width to req.height)
        assertEquals(s.rig.engine.latestJpeg(), s.redactorCalls.single())
        assertTrue(Regex("[A-Za-z0-9_-]{8,64}").matches(id), id)

        s.transport.answer(s.ok(id, "A doorway is open on your right. A person is farther ahead near the wall."))
        assertTrue(s.speech.descriptions.isEmpty(), "callback thread only enqueues")
        s.c.tick(s.rig.now)
        assertEquals(listOf("May be wrong. A doorway is open on your right. A person is farther ahead near the wall."), s.speech.descriptions)
        assertEquals(DescribeState.SPEAKING, s.c.state)

        // A new question while speaking: allowed, replaces the old answer, gets a new id.
        val id2 = s.start()
        assertNotEquals(id, id2)
        assertEquals(1, s.speech.stops)
    }

    @Test
    fun `rejections in order, without upload`() {
        val s = Setup(DescribeConfig(maxFrameAgeUs = 100_000))
        s.c.enabled = false
        assertEquals(DescribeOutcome.DISABLED, s.c.request(s.rig.now))
        s.c.enabled = true

        val id = s.start()
        assertEquals(DescribeOutcome.BUSY, s.c.request(s.rig.now))
        s.transport.answer(DescribeResult.Failed(id, "unavailable", true))
        s.c.tick(s.rig.now)
        assertEquals(DescribeState.IDLE, s.c.state)

        s.rig.phoneFrames = false
        s.rig.run(150_000) // frame older than 100 ms, system still READY
        assertEquals(SystemState.READY, s.rig.engine.state)
        assertEquals(DescribeOutcome.NO_FRESH_FRAME, s.c.request(s.rig.now))

        s.rig.run(500_000)
        assertEquals(SystemState.UNAVAILABLE, s.rig.engine.state)
        s.c.enabled = true
        assertEquals(DescribeOutcome.SYSTEM_UNAVAILABLE, s.c.request(s.rig.now))
        assertEquals(1, s.transport.sent.size)
        assertEquals(1, s.redactorCalls.size)
    }

    @Test
    fun `no frame at all is not fresh`() {
        val e = FallbackRig(EngineConfig(frameSource = FrameSourceKind.PHONE_CAMERA), phoneFrames = false)
        val t = FakeTransport()
        val c = DescribeController(e.engine, t, { ByteArray(1) }, FakeSpeech()).also { it.enabled = true }
        assertEquals(DescribeOutcome.NO_FRESH_FRAME, c.request(e.now))
        assertTrue(t.sent.isEmpty())
    }

    @Test
    fun `disabled by default`() {
        val s = Setup()
        val c = DescribeController(s.rig.engine, s.transport, { ByteArray(1) }, s.speech)
        assertEquals(DescribeOutcome.DISABLED, c.request(s.rig.now))
    }

    @Test
    fun `no upload when redaction fails or throws`() {
        val s = Setup(redact = { null })
        assertEquals(DescribeOutcome.REDACTION_FAILED, s.c.request(s.rig.now))
        val t = Setup(redact = { throw IllegalStateException("face detector crashed") })
        assertEquals(DescribeOutcome.REDACTION_FAILED, t.c.request(t.rig.now))
        for (x in listOf(s, t)) {
            assertTrue(x.transport.sent.isEmpty())
            assertEquals(listOf("Description unavailable."), x.speech.said)
            assertEquals(DescribeState.IDLE, x.c.state)
        }
    }

    @Test
    fun `failure and timeout say unavailable, late result ignored`() {
        val s = Setup()
        val id = s.start()
        s.c.onResult(DescribeResult.Failed(id, "inference_failed", false), s.rig.now)
        assertEquals(listOf("Description unavailable."), s.speech.said)

        val t0 = s.rig.now
        val id2 = s.start()
        s.c.tick(t0 + 7_999_999)
        assertEquals(DescribeState.WAITING, s.c.state)
        s.c.tick(t0 + 8_000_000)
        assertEquals(DescribeState.IDLE, s.c.state)
        assertEquals(listOf(id2), s.transport.cancelled)
        assertEquals(2, s.speech.said.size)
        s.transport.answer(s.ok(id2, "A door is on your left."))
        s.c.tick(t0 + 8_100_000)
        s.c.onResult(s.ok(id2, "A door is on your left."), t0 + 8_100_000)
        assertTrue(s.speech.descriptions.isEmpty())
        assertEquals(2, s.speech.said.size)
    }

    @Test
    fun `mid and near interrupt, far does not`() {
        val s = Setup()
        val id = s.start()
        s.c.onAlert(alert(Band.FAR), s.rig.now)
        assertEquals(DescribeState.WAITING, s.c.state)
        assertTrue(s.transport.cancelled.isEmpty())
        s.c.onAlert(null, s.rig.now)
        s.c.onResult(s.ok(id, "A bench is on your right."), s.rig.now)
        assertEquals(DescribeState.SPEAKING, s.c.state)

        s.c.onAlert(alert(Band.NEAR), s.rig.now) // while speaking
        assertEquals(DescribeState.IDLE, s.c.state)
        assertTrue(s.speech.stops >= 1)
        s.c.onAlert(null, s.rig.now)

        val id2 = s.start()
        val stops = s.speech.stops
        s.c.onAlert(alert(Band.MID), s.rig.now) // while waiting
        assertEquals(DescribeState.IDLE, s.c.state)
        assertEquals(listOf(id2), s.transport.cancelled)
        assertEquals(stops + 1, s.speech.stops)
        s.transport.answer(s.ok(id2, "A door is on your left."))
        s.c.tick(s.rig.now) // late answer after the interrupt: never spoken
        assertEquals(1, s.speech.descriptions.size)
        assertTrue(s.speech.said.isEmpty(), "interrupts are silent: the hazard owns the audio")
    }

    @Test
    fun `answer is dropped while a mid hazard is active`() {
        val s = Setup()
        s.c.onAlert(alert(Band.MID), s.rig.now)
        val id = s.start()
        s.c.onResult(s.ok(id, "A door is on your left."), s.rig.now)
        assertTrue(s.speech.descriptions.isEmpty())
        assertEquals(DescribeState.IDLE, s.c.state)
    }

    @Test
    fun `system unavailable and opting out cancel`() {
        val s = Setup()
        val id = s.start()
        s.c.onState(SystemState.DEGRADED, s.rig.now)
        assertEquals(DescribeState.WAITING, s.c.state)
        s.c.onState(SystemState.UNAVAILABLE, s.rig.now)
        assertEquals(DescribeState.IDLE, s.c.state)
        assertEquals(listOf(id), s.transport.cancelled)
        s.c.onResult(s.ok(id, "A door is on your left."), s.rig.now)
        assertTrue(s.speech.descriptions.isEmpty())

        val id2 = s.start()
        s.c.enabled = false
        assertEquals(DescribeState.IDLE, s.c.state)
        assertEquals(listOf(id, id2), s.transport.cancelled)
    }

    @Test
    fun `reassurance is stripped locally, nothing left is unavailable`() {
        val s = Setup()
        val id = s.start()
        s.c.onResult(s.ok(id, "The path ahead is clear. A door is on your left."), s.rig.now)
        assertEquals(listOf("May be wrong. A door is on your left."), s.speech.descriptions)

        val id2 = s.start()
        s.c.onResult(s.ok(id2, "Nothing ahead. It is SAFE to walk!"), s.rig.now)
        assertEquals(1, s.speech.descriptions.size)
        assertEquals(listOf("Description unavailable."), s.speech.said)
        assertEquals(DescribeState.IDLE, s.c.state)
    }

    @Test
    fun `sanitize keeps hazards and drops every listed reassurance`() {
        assertEquals("An unclear shape is on your right.", DescribeController.sanitize("An unclear shape is on your right."))
        for (bad in listOf("All clear.", "The way is clearly open.", "You can walk safely.", "There are no obstacles.",
            "No obstacle here.", "An empty path leads on.", "The hall is free of obstacles.", "Nothing ahead.")) {
            assertNull(DescribeController.sanitize(bad), bad)
        }
        assertEquals("Steps go down ahead. A pole is on your left.",
            DescribeController.sanitize("Steps go down ahead. It is safe. A pole is on your left."))
        assertNull(DescribeController.sanitize("   "))
    }

    @Test
    fun `speaking ends after the estimated utterance`() {
        val s = Setup()
        val id = s.start()
        s.c.onResult(s.ok(id, "A door is on your left."), s.rig.now)
        assertEquals(DescribeState.SPEAKING, s.c.state)
        s.c.tick(s.rig.now + 1_000_000)
        assertEquals(DescribeState.SPEAKING, s.c.state)
        s.c.tick(s.rig.now + 30_000_000)
        assertEquals(DescribeState.IDLE, s.c.state)
    }
}
