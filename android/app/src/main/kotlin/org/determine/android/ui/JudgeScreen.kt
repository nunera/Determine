package org.determine.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.determine.android.runtime.DetermineRuntime
import org.determine.core.BoardDiagnostics
import org.determine.core.RangeSnapshot
import org.determine.core.CueOutput
import org.determine.core.FrameSourceKind

/**
 * Judge diagnostics (Arch §11.4): both streams' age, the 8×8 ToF grid with millimetres, the chosen alert,
 * measured latency p50/p95, board faults. Numbers only from the engine; nothing here is simulated.
 */
@Composable
fun JudgeScreen(onBack: () -> Unit) {
    val running by DetermineRuntime.running.collectAsStateWithLifecycle()
    val d by DetermineRuntime.diagnostics.collectAsStateWithLifecycle()
    val range by DetermineRuntime.range.collectAsStateWithLifecycle()
    val det by DetermineRuntime.detector.collectAsStateWithLifecycle()
    val link by DetermineRuntime.link.collectAsStateWithLifecycle()
    val alert by DetermineRuntime.alert.collectAsStateWithLifecycle()
    val fallbacks by DetermineRuntime.fallbacks.collectAsStateWithLifecycle()
    val describeInfo by DetermineRuntime.describe.collectAsStateWithLifecycle()

    Screen(title = "Judge diagnostics", onBack = onBack) {
        if (!running) {
            Text("Determine is stopped. Start it on the main screen to see live data.", style = MaterialTheme.typography.bodyLarge)
            return@Screen
        }
        val diag = d
        SectionHeading("Streams")
        fallbacks?.let { f ->
            val fb = " — software fallback, not the proposed glasses"
            Metric("Frame source", if (f.frameSource == FrameSourceKind.PHONE_CAMERA) "phone camera$fb" else "glasses camera board")
            Metric("Cue output", if (f.cueOutput == CueOutput.PHONE_AUDIO) "phone audio$fb" else "glasses transducers")
            f.phoneCameraError?.let { Metric("Phone camera error", it) }
            f.phoneAudioError?.let { Metric("Phone audio error", it) }
        }
        Metric("State", diag?.state?.name ?: "-")
        Metric("Camera frame age", ms(diag?.frameAgeMs))
        Metric("ToF grid age", ms(diag?.rangeAgeMs))
        diag?.let {
            BoardBlock("Camera board", it.cam, link?.camIp)
            BoardBlock("Cue board", it.cue, link?.cueIp)
        }

        SectionHeading("Chosen alert")
        Text(alert?.let { describe(it) } ?: "No alert (not a clear path)", style = MaterialTheme.typography.titleMedium)

        SectionHeading("Latency (measured)")
        Metric("Capture to cue, last", msD(diag?.lastCueLatencyMs))
        Metric("Capture to cue p50 / p95", "${msD(diag?.captureToCueP50Ms)} / ${msD(diag?.captureToCueP95Ms)}")
        Metric("Cue to ACK p50 / p95", "${msD(diag?.ackP50Ms)} / ${msD(diag?.ackP95Ms)}")
        Metric("Detector inference, last", msD(det?.lastInferenceMs))

        SectionHeading("Describe (Annapurna silicon, measured)")
        val di = describeInfo
        if (di == null) {
            Text("No description requested this session.", style = MaterialTheme.typography.bodyMedium)
        } else if (di.ok) {
            Metric("Backend / silicon", "${di.backend} / ${di.silicon}")
            Metric("Model inference", ms(di.inferenceMs))
            Metric("Server total", ms(di.serverTotalMs))
            Metric("Phone round trip", ms(di.roundTripMs))
        } else {
            Metric("Last request failed", "${di.reason} after ${di.roundTripMs} ms")
        }
        fallbacks?.takeIf { !it.redactorAvailable }?.let { Metric("Face blur", "unavailable: no uploads") }

        SectionHeading("ToF grid (mm)")
        RangeGrid(range)

        SectionHeading("Counters")
        diag?.let {
            Metric("Cues sent / ACKs", "${it.cuesSent} / ${it.acks}")
            Metric("NACKs / missed ACKs", "${it.nacks} / ${it.missedAcks}")
            Metric("Malformed / stale dropped", "${it.malformed} / ${it.staleDropped}")
        }
        det?.let {
            Metric("Detector", if (it.available) "MediaPipe EfficientDet-Lite0" else "unavailable: ${it.error ?: "unknown"} (ToF only)")
            Metric("Frames detected / busy-skipped", "${it.submitted} / ${it.skippedBusy}")
            Metric("Stale-skipped / abandoned / bad JPEG", "${it.skippedStale} / ${it.abandoned} / ${it.decodeFailures}")
        }
        link?.let {
            Metric("UDP :47000", if (it.bound) "listening" else "FAILED: ${it.bindError}")
            Metric("Unattributed packets / send errors", "${it.unattributedPackets} / ${it.sendErrors}")
        }
    }
}

@Composable
private fun BoardBlock(name: String, b: BoardDiagnostics, ip: String?) {
    Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline).padding(12.dp)) {
        Text(name, style = MaterialTheme.typography.titleMedium)
        Metric("Heartbeat age", ms(b.beatAgeMs))
        Metric("Clock", if (b.synced) "synced, offset ${b.offsetUs?.let { "${it / 1000} ms" } ?: "-"}, RTT ${b.rttUs?.let { "%.1f ms".format(it / 1000.0) } ?: "-"}" else "NOT synced")
        Metric("IP / RSSI", "${ip ?: "not heard"} / ${b.rssiDbm?.let { "$it dBm" } ?: "-"}")
        Metric("Faults", faultText(b.faults))
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 8.dp).weight(1f))
    }
}

private fun ms(v: Long?) = v?.let { "$it ms" } ?: "-"
private fun msD(v: Double?) = v?.let { "%.0f ms".format(it) } ?: "-"

/** PROTOCOL.md RANGE status: 5 and 9 valid, 255 NO_TARGET, anything else UNKNOWN (never "far"). */
private fun zoneText(mm: Int, status: Int): String = when (status) {
    5, 9 -> mm.toString()
    255 -> "none"
    else -> "?"
}

/**
 * 8×8 heatmap, row 0 = top of the wearer's view, col 0 = wearer's left (PROTOCOL.md). Bands follow Arch §7.2
 * (NEAR < 1000, MID < 2000, FAR < 3000 mm). Unknown zones are grey "?", never coloured as far.
 * TalkBack reads one row at a time.
 */
@Composable
private fun RangeGrid(r: RangeSnapshot?) {
    if (r == null) {
        Text("No ToF grid received yet", style = MaterialTheme.typography.bodyLarge)
        return
    }
    Column(Modifier.fillMaxWidth().border(2.dp, MaterialTheme.colorScheme.outline)) {
        for (row in 0 until 8) {
            val rowText = (0 until 8).joinToString(", ") { col -> zoneText(r.mm[row * 8 + col], r.status[row * 8 + col]) }
            Row(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "Row ${row + 1}, left to right: $rowText" }) {
                for (col in 0 until 8) {
                    val i = row * 8 + col
                    val (bg, fg) = zoneColors(r.mm[i], r.status[i])
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).background(bg).border(0.5.dp, Palette.Rule),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(zoneText(r.mm[i], r.status[i]), color = fg, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
    Text("Left of grid = wearer's left. Yellow < 1 m, orange < 2 m, blue < 3 m, grey ? = unknown.",
        style = MaterialTheme.typography.bodySmall)
}

private fun zoneColors(mm: Int, status: Int): Pair<Color, Color> = when {
    status != 5 && status != 9 && status != 255 -> Color(0xFF8A8D94) to Color.Black // UNKNOWN
    status == 255 -> Color.White to Palette.MutedInk // NO_TARGET
    mm < 1000 -> Palette.Hazard to Color.Black
    mm < 2000 -> Color(0xFFFF9F43) to Color.Black
    mm < 3000 -> Color(0xFFA9C0FF) to Color.Black
    else -> Color.White to Color.Black
}
