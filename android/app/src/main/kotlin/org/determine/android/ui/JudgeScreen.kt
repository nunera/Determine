package org.determine.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.determine.android.runtime.DetermineRuntime
import org.determine.android.runtime.HeadsetInfo
import org.determine.core.Band
import org.determine.core.hearing.HearingMode
import org.determine.core.BoardDiagnostics
import org.determine.core.FrameSourceKind
import org.determine.core.RangeSnapshot
import org.determine.core.Tuning
import org.determine.core.wire.RangeStatus

/**
 * Judge diagnostics (Arch §11.4): stream ages, the headset route, the ONE forward distance with its status, the
 * chosen alert, measured latency p50/p95 and board faults. Numbers only from the engine; nothing is simulated.
 * Works without a paired headset: the route then reads "not connected" and the forward measurement stays visible.
 */
@Composable
fun JudgeScreen(onBack: () -> Unit) {
    val running by DetermineRuntime.running.collectAsStateWithLifecycle()
    val d by DetermineRuntime.diagnostics.collectAsStateWithLifecycle()
    val range by DetermineRuntime.range.collectAsStateWithLifecycle()
    val link by DetermineRuntime.link.collectAsStateWithLifecycle()
    val headset by DetermineRuntime.headset.collectAsStateWithLifecycle()
    val alert by DetermineRuntime.alert.collectAsStateWithLifecycle()
    val state by DetermineRuntime.state.collectAsStateWithLifecycle()
    val fallbacks by DetermineRuntime.fallbacks.collectAsStateWithLifecycle()
    val describeInfo by DetermineRuntime.describe.collectAsStateWithLifecycle()
    val profile by DetermineRuntime.profile.collectAsStateWithLifecycle()

    Screen(title = "Judge diagnostics", onBack = onBack) {
        if (!running) {
            Text("Determine is stopped. Start it on the main screen to see live data.", style = MaterialTheme.typography.bodyLarge)
            return@Screen
        }
        val diag = d
        SectionHeading("Streams")
        fallbacks?.let { f ->
            Metric(
                "Frame source",
                if (f.frameSource == FrameSourceKind.PHONE_CAMERA) "phone camera — software fallback, not the proposed glasses"
                else "glasses camera board",
            )
            f.phoneCameraError?.let { Metric("Phone camera error", it) }
        }
        Metric("State", diag?.state?.name?.let { s -> state?.reason?.takeIf { it.isNotBlank() }?.let { "$s (${it.replace('_', ' ')})" } ?: s } ?: "-")
        Metric("Camera frame age", ms(diag?.frameAgeMs))
        Metric("Forward distance age", ms(diag?.rangeAgeMs))
        HeadsetBlock(headset, audioOff = profile?.mode == HearingMode.HAPTIC_ONLY)
        diag?.let {
            BoardBlock("Camera board", it.cam, link?.camIp)
            BoardBlock("Cue board (forward ToF + temple haptics)", it.cue, link?.cueIp)
        }

        SectionHeading("Forward distance")
        ForwardRange(range)

        SectionHeading("Chosen alert")
        Text(alert?.let { describe(it) } ?: "No alert (not a clear path)", style = MaterialTheme.typography.titleMedium)

        SectionHeading("Latency (measured)")
        Metric("Capture to cue, last", msD(diag?.lastCueLatencyMs))
        Metric("Capture to cue p50 / p95", "${msD(diag?.captureToCueP50Ms)} / ${msD(diag?.captureToCueP95Ms)}")
        Metric("Cue to ACK p50 / p95", "${msD(diag?.ackP50Ms)} / ${msD(diag?.ackP95Ms)}")

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

        SectionHeading("Counters")
        diag?.let {
            Metric("Cues sent / ACKs", "${it.cuesSent} / ${it.acks}")
            Metric("NACKs / missed ACKs", "${it.nacks} / ${it.missedAcks}")
            Metric("Malformed / stale dropped", "${it.malformed} / ${it.staleDropped}")
        }
        link?.let {
            Metric("UDP :47000", if (it.bound) "listening" else "FAILED: ${it.bindError}")
            Metric("Unattributed packets / send errors", "${it.unattributedPackets} / ${it.sendErrors}")
        }
    }
}

/**
 * The audio route: Bluetooth bone-conduction headset or nothing (never the phone speaker). With warning sounds off
 * on purpose (haptic only) no headset is needed and its absence is not a failure.
 */
@Composable
private fun HeadsetBlock(h: HeadsetInfo?, audioOff: Boolean) {
    Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline).padding(12.dp)) {
        Text("Headset audio (Bluetooth bone conduction)", style = MaterialTheme.typography.titleMedium)
        val route = when {
            audioOff -> "not required: warning sounds are off (haptic only)" +
                (if (h?.routeReady == true) ", headset ready: ${h.name ?: "headset"}" else "")
            h == null -> "-"
            h.routeReady -> "ready: ${h.name ?: "headset"}"
            h.present -> "headset ${h.name ?: ""} found, audio route not ready".replace("  ", " ")
            else -> "NOT CONNECTED: warnings unavailable"
        }
        Metric("Route", route)
        h?.takeIf { !it.routeReady && !audioOff }?.error?.let { Metric("Reason", it) }
        Text(
            "Warning sounds play only on this headset. Without it the system reports system off, unless warning " +
                "sounds are switched off (haptic only); it never falls back to the phone speaker.",
            style = MaterialTheme.typography.bodySmall,
        )
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

/** RANGE v2 canonical status: VALID reads as millimetres; NO_TARGET and UNKNOWN never read as a distance. */
internal fun rangeText(mm: Int, status: Int): String = when (status) {
    RangeStatus.VALID -> "$mm mm"
    RangeStatus.NO_TARGET -> "no target"
    RangeStatus.UNKNOWN -> "unknown"
    else -> "invalid status $status"
}

/** Band for a VALID reading using the engine's default (proposed, tunable) thresholds; null otherwise. */
internal fun bandOf(r: RangeSnapshot): Band? =
    if (r.status != RangeStatus.VALID) null else with(Tuning()) {
        when {
            r.mm < nearMm -> Band.NEAR
            r.mm < midMm -> Band.MID
            r.mm <= farMm -> Band.FAR
            else -> Band.NONE
        }
    }

/**
 * One forward measurement: the single ToF zone straight ahead. Left and right are shown as "not measured" — one
 * sensor cannot say what is beside the wearer, so nothing is inferred and nothing is drawn as far.
 */
@Composable
private fun ForwardRange(r: RangeSnapshot?) {
    if (r == null) {
        Text("No forward distance received yet", style = MaterialTheme.typography.bodyLarge)
        return
    }
    val band = bandOf(r)
    val (bg, fg) = bandColors(band, r.status)
    val text = rangeText(r.mm, r.status)
    val bandText = when (band) {
        Band.NEAR -> "near"
        Band.MID -> "mid"
        Band.FAR -> "far"
        Band.NONE -> "beyond ${Tuning().farMm} mm: not reported"
        null -> if (r.status == RangeStatus.NO_TARGET) "nothing seen: not a clear path" else "do not know: not far, not clear"
    }
    Column(
        Modifier
            .fillMaxWidth()
            .border(2.dp, MaterialTheme.colorScheme.outline)
            .background(bg)
            .padding(16.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Forward distance: $text, $bandText" },
    ) {
        Text("Ahead", style = MaterialTheme.typography.labelMedium, color = fg)
        Text(text, style = MaterialTheme.typography.displaySmall, color = fg, fontFamily = FontFamily.Monospace)
        Text(bandText, style = MaterialTheme.typography.bodyLarge, color = fg)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for (side in listOf("Left", "Right")) {
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 64.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(8.dp)
                    .clearAndSetSemantics { contentDescription = "$side: not measured, one forward sensor only" },
                contentAlignment = Alignment.Center,
            ) {
                Text("$side: not measured", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    val c = Tuning()
    Text(
        "One forward sensor. Bands are proposed and tunable: near under ${c.nearMm} mm, mid ${c.nearMm}–${c.midMm - 1} mm, " +
            "far ${c.midMm}–${c.farMm} mm; beyond ${c.farMm} mm nothing is reported. Yellow near, orange mid, blue far, grey " +
            "unknown. Left and right are not measured.",
        style = MaterialTheme.typography.bodySmall,
    )
}

private fun bandColors(band: Band?, status: Int): Pair<Color, Color> = when {
    status != RangeStatus.VALID && status != RangeStatus.NO_TARGET -> Color(0xFF8A8D94) to Color.Black // UNKNOWN
    status == RangeStatus.NO_TARGET -> Color.White to Palette.MutedInk
    band == Band.NEAR -> Palette.Hazard to Color.Black
    band == Band.MID -> Color(0xFFFF9F43) to Color.Black
    band == Band.FAR -> Color(0xFFA9C0FF) to Color.Black
    else -> Color.White to Color.Black
}
