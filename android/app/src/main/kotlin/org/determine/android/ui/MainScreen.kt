package org.determine.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.determine.android.runtime.DetermineRuntime
import org.determine.core.Band
import org.determine.core.SystemState
import org.determine.core.hearing.HearingMode
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import org.determine.android.DetermineApplication
import org.determine.core.describe.DescribeOutcome

@Composable
fun MainScreen(
    onStart: () -> Unit,
    onStop: () -> Unit,
    onJudge: () -> Unit,
    onCalibrate: () -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val running by DetermineRuntime.running.collectAsStateWithLifecycle()
    val stateInfo by DetermineRuntime.state.collectAsStateWithLifecycle()
    val alert by DetermineRuntime.alert.collectAsStateWithLifecycle()
    val profile by DetermineRuntime.profile.collectAsStateWithLifecycle()

    val state = stateInfo?.state
    val stateText = describe(context, state)
    val failing = running && state == SystemState.UNAVAILABLE

    Screen(title = "Determine", onBack = null) {
        // The state is the one thing announced automatically (assertive live region): a change to
        // UNAVAILABLE or PAUSED must reach a TalkBack user without exploring the screen.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(3.dp, MaterialTheme.colorScheme.outline)
                .background(if (failing) MaterialTheme.colorScheme.error else Color.Transparent)
                .padding(16.dp)
                .semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Assertive
                    contentDescription = "Status: $stateText"
                },
        ) {
            Text("Status", style = MaterialTheme.typography.labelMedium,
                color = if (failing) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onBackground)
            Text(stateText, style = MaterialTheme.typography.displaySmall,
                color = if (failing) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onBackground)
            stateInfo?.reason?.takeIf { running && it.isNotBlank() }?.let {
                Text(it.replace('_', ' '), style = MaterialTheme.typography.bodyMedium,
                    color = if (failing) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onBackground)
            }
        }

        // The current warning, if any. Not a live region: the earcon and spearcon already announce it, and a
        // second spoken copy would mask the cue. When there is no warning nothing is shown: silence is not
        // a clear path, and the app never claims one.
        alert?.takeIf { running && it.band != Band.NONE }?.let { a ->
            val near = a.band == Band.NEAR
            Text(
                describe(a),
                style = MaterialTheme.typography.headlineMedium,
                color = if (near) Color.Black else MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (near) Palette.Hazard else Color.Transparent)
                    .border(2.dp, MaterialTheme.colorScheme.outline)
                    .padding(16.dp)
                    .semantics { contentDescription = "Current warning: ${describe(a)}" },
            )
        }

        Text(
            "Silence means nothing to report from a working system. It never means the path is clear. " +
                "Keep using your cane or guide dog.",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (running) {
            BigButton("Stop Determine", onStop, primary = false)
            val paused = state == SystemState.PAUSED
            BigButton(
                if (paused) "Resume warnings" else "Pause warnings",
                { DetermineRuntime.setPaused(!paused) },
            )
        } else {
            BigButton("Start Determine", onStart)
        }

        val settings = (context.applicationContext as DetermineApplication).settings
        val describeOn by settings.describeEnabled.collectAsStateWithLifecycle()
        val outcome by DetermineRuntime.describeOutcome.collectAsStateWithLifecycle()
        if (running && describeOn) {
            WhatsAheadButton()
            outcome?.outcome?.takeIf { it != DescribeOutcome.STARTED }?.let { o ->
                // Polite live region: a refusal is announced once, without interrupting a warning.
                Text(
                    outcomeText(o),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }

        val p = profile
        val profileText = when {
            p == null -> "Hearing profile: not calibrated this session"
            p.deviceId == "uncalibrated" -> "Hearing profile: not calibrated this session (quiet default levels)"
            else -> "Hearing profile: ${modeName(p.mode)}, verified ${p.verifiedCorrect} of ${p.verifiedTrials}"
        }
        Text(profileText, style = MaterialTheme.typography.bodyMedium)
        BigButton("Calibrate hearing", onCalibrate, primary = false)
        BigButton("Judge diagnostics", onJudge, primary = false)
        BigButton("Settings", onSettings, primary = false)
    }
}

/**
 * Arch §7.3: long press only, so a stray tap never uploads a frame. TalkBack users get a custom action with the
 * same effect (a long press is awkward under explore-by-touch).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WhatsAheadButton() {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp)
            .border(3.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
            .combinedClickable(
                onClick = {},
                onLongClick = { DetermineRuntime.describeAhead() },
                onLongClickLabel = "Describe what's ahead",
            )
            .semantics {
                contentDescription = "What's ahead? Long-press to describe"
                customActions = listOf(CustomAccessibilityAction("Describe what's ahead") { DetermineRuntime.describeAhead(); true })
            }
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("What's ahead?  (long-press)", style = MaterialTheme.typography.headlineSmall)
    }
}

private fun outcomeText(o: DescribeOutcome): String = when (o) {
    DescribeOutcome.STARTED -> ""
    DescribeOutcome.DISABLED -> "Description is off in Settings."
    DescribeOutcome.SYSTEM_UNAVAILABLE -> "Description unavailable while the system is off."
    DescribeOutcome.NO_FRESH_FRAME -> "Description unavailable: no recent camera frame."
    DescribeOutcome.BUSY -> "Already describing."
    DescribeOutcome.REDACTION_FAILED -> "Description unavailable: faces could not be blurred, nothing was sent."
}

fun modeName(mode: HearingMode): String = when (mode) {
    HearingMode.STEREO -> "stereo"
    HearingMode.SSD -> "one-ear mode (mono audio, direction by haptics)"
    HearingMode.HAPTIC_ONLY -> "haptic only"
}
