package org.determine.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.determine.android.runtime.CalibrationAction
import org.determine.android.runtime.CalibrationView
import org.determine.android.runtime.DetermineRuntime
import org.determine.core.hearing.CalBand
import org.determine.core.hearing.CalStep
import org.determine.core.hearing.CalibrationSession
import org.determine.core.hearing.Ear
import org.determine.core.hearing.HARD_CEILING_DB
import kotlin.math.roundToInt

/**
 * Arch §9 calibration flow (fit → threshold per side in 3 bands → level → balance → verify → mode), fully
 * operable with TalkBack: every answer is a big labelled button, the step heading is a polite live region.
 * Levels are computed by the core [CalibrationSession]; no step can exceed the ceiling (Arch §9.1), and the
 * screen never shows results as audiometry.
 */
@Composable
fun CalibrationScreen(onBack: () -> Unit) {
    val running by DetermineRuntime.running.collectAsStateWithLifecycle()
    val view by DetermineRuntime.calibration.collectAsStateWithLifecycle()
    val headset by DetermineRuntime.headset.collectAsStateWithLifecycle()
    var applying by remember { mutableStateOf(false) }

    // A commit is asynchronous. Leave only after the host actually clears the session; a failed commit stays visible.
    LaunchedEffect(view, applying) {
        if (applying && view == null) onBack()
        if (view?.step == CalStep.FAILED) applying = false
    }
    // Leaving mid-calibration cancels it, which also lifts the calibration pause on obstacle cues.
    val leave = {
        if (view != null) DetermineRuntime.cancelCalibration()
        onBack()
    }
    BackHandler(enabled = view != null) { leave() }

    Screen(title = "Hearing calibration", onBack = leave) {
        if (!running) {
            Text("Start Determine first. Calibration plays its tones on your Bluetooth headset.", style = MaterialTheme.typography.bodyLarge)
            return@Screen
        }
        val v = view
        if (v == null) {
            Intro(headsetReady = headset?.routeReady == true)
            return@Screen
        }
        StepHeading(v)
        when (v.step) {
            CalStep.FIT -> FitStep()
            CalStep.THRESHOLD -> ThresholdStep()
            CalStep.LEVEL -> LevelStep()
            CalStep.BALANCE -> BalanceStep(v)
            CalStep.VERIFY -> VerifyStep()
            CalStep.DONE -> DoneStep(v, { applying = true }, leave)
            CalStep.FAILED -> FailedStep(v, headset?.routeReady == true, leave)
        }
        if (v.step != CalStep.FAILED) {
            Text("Ceiling: ${minOf(v.ceilingDb, HARD_CEILING_DB)} dBFS. No step plays louder than this.",
                style = MaterialTheme.typography.bodySmall)
        }
        if (v.step != CalStep.DONE && v.step != CalStep.FAILED) {
            BigButton("Use haptics only instead", { DetermineRuntime.calibration(CalibrationAction.HapticOnly) }, primary = false)
            BigButton("Cancel calibration", leave, primary = false)
        }
    }
}

@Composable
private fun Intro(headsetReady: Boolean) {
    Text(
        "This matches how loud each side feels to you with the headset as it sits today. It is not a hearing " +
            "test and does not restore hearing. If you have known hearing loss, see an audiologist.",
        style = MaterialTheme.typography.bodyLarge,
    )
    Text("It takes about 3 minutes. Obstacle warnings are paused until you finish or cancel.",
        style = MaterialTheme.typography.bodyLarge)
    if (!headsetReady) {
        Text(
            "No Bluetooth headset is connected, so tones cannot play. Pair and connect your bone-conduction " +
                "headset. Calibration needs it; haptic-only warnings do not. Nothing plays on the phone speaker.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
    }
    BigButton("Start calibration", { DetermineRuntime.startCalibration() }, enabled = headsetReady)
}

@Composable
private fun StepHeading(v: CalibrationView) {
    val text = when (v.step) {
        CalStep.FIT -> "Step 1 of 5: fit"
        CalStep.THRESHOLD -> {
            val side = if (v.ear == Ear.LEFT) "Left" else "Right"
            val band = (v.bandIndex ?: 0) + 1
            "Step 2 of 5: quietest tone. $side side, tone ${band} of ${CalBand.HZ.size} (${v.bandHz} hertz)"
        }
        CalStep.LEVEL -> "Step 3 of 5: comfortable level"
        CalStep.BALANCE -> "Step 4 of 5: balance, check ${v.balanceConfirms + 1} of 2"
        CalStep.VERIFY -> "Step 5 of 5: which side? Trial ${v.verifyTrials + 1} of ${CalibrationSession.VERIFY_TRIALS}"
        CalStep.DONE -> "Calibration finished"
        CalStep.FAILED -> "Calibration stopped"
    }
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun FitStep() {
    var weaker by rememberSaveable { mutableStateOf<String?>(null) }
    Text("Seat the headset as you will wear it. Tones play on your Bluetooth bone-conduction headset.",
        style = MaterialTheme.typography.bodyLarge)
    Text("Which side is weaker, if any?", style = MaterialTheme.typography.titleMedium)
    Column(Modifier.selectableGroup()) {
        listOf("LEFT" to "Left is weaker", "RIGHT" to "Right is weaker", null to "Neither or not sure").forEach { (key, label) ->
            ChoiceRow(label, selected = weaker == key) { weaker = key }
        }
    }
    BigButton("Continue", {
        DetermineRuntime.calibration(CalibrationAction.Fit(DEVICE_ID, weaker?.let { Ear.valueOf(it) }))
    })
}

@Composable
private fun ThresholdStep() {
    Text("A short tone plays. Answer every time, even if unsure.", style = MaterialTheme.typography.bodyLarge)
    BigButton("Heard it", { DetermineRuntime.calibration(CalibrationAction.Heard(true)) })
    BigButton("Did not hear it", { DetermineRuntime.calibration(CalibrationAction.Heard(false)) })
    BigButton("Play again", { DetermineRuntime.calibration(CalibrationAction.Replay) }, primary = false)
}

@Composable
private fun LevelStep() {
    var level by rememberSaveable { mutableIntStateOf(15) }
    Text(
        "How far above your quietest level should warnings play? Higher is louder but always stays under the ceiling.",
        style = MaterialTheme.typography.bodyLarge,
    )
    Column(Modifier.selectableGroup()) {
        listOf(10, 12, 15, 18, 20).forEach { db ->
            val label = when (db) {
                10 -> "10 dB: softest"
                15 -> "15 dB: middle"
                20 -> "20 dB: loudest"
                else -> "$db dB"
            }
            ChoiceRow(label, selected = level == db) { level = db }
        }
    }
    BigButton("Continue", { DetermineRuntime.calibration(CalibrationAction.SensationLevel(level)) })
}

@Composable
private fun BalanceStep(v: CalibrationView) {
    Text(
        "The obstacle sound alternates left and right. Adjust until both sides feel equally loud.",
        style = MaterialTheme.typography.bodyLarge,
    )
    BigButton("Make left louder", { DetermineRuntime.calibration(CalibrationAction.NudgeBalance(-BALANCE_STEP_DB)) })
    BigButton("Make right louder", { DetermineRuntime.calibration(CalibrationAction.NudgeBalance(BALANCE_STEP_DB)) })

    // Slider for sighted helpers; TalkBack users can also swipe up/down on it. Only the released value is
    // sent, so dragging does not fire a burst of cues.
    var slider by remember(v.balanceDb) { mutableFloatStateOf(v.balanceDb.toFloat()) }
    val balanceText = when {
        v.balanceDb < 0 -> "left louder by ${-v.balanceDb} dB"
        v.balanceDb > 0 -> "right louder by ${v.balanceDb} dB"
        else -> "centred"
    }
    Text("Balance: $balanceText", style = MaterialTheme.typography.titleMedium)
    Slider(
        value = slider,
        onValueChange = { slider = it },
        onValueChangeFinished = {
            val delta = slider.roundToInt() - v.balanceDb
            if (delta != 0) DetermineRuntime.calibration(CalibrationAction.NudgeBalance(delta))
        },
        valueRange = -BALANCE_RANGE_DB.toFloat()..BALANCE_RANGE_DB.toFloat(),
        steps = BALANCE_RANGE_DB * 2 - 1,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Balance"
                stateDescription = balanceText
            },
    )
    BigButton("Play again", { DetermineRuntime.calibration(CalibrationAction.Replay) }, primary = false)
    BigButton("Both sides sound equal", { DetermineRuntime.calibration(CalibrationAction.ConfirmBalance) })
}

@Composable
private fun VerifyStep() {
    Text("Name the side you hear the sound on.", style = MaterialTheme.typography.bodyLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            BigButton("Left", { DetermineRuntime.calibration(CalibrationAction.NameSide(Ear.LEFT)) })
        }
        Column(Modifier.weight(1f)) {
            BigButton("Right", { DetermineRuntime.calibration(CalibrationAction.NameSide(Ear.RIGHT)) })
        }
    }
    BigButton("Play again", { DetermineRuntime.calibration(CalibrationAction.Replay) }, primary = false)
}

@Composable
private fun DoneStep(v: CalibrationView, onApply: () -> Unit, onDiscard: () -> Unit) {
    val p = v.result ?: return
    Text("Mode: ${modeName(p.mode)}", style = MaterialTheme.typography.headlineMedium)
    if (p.verifiedTrials > 0) {
        Text("Side naming: ${p.verifiedCorrect} of ${p.verifiedTrials} correct.", style = MaterialTheme.typography.bodyLarge)
    }
    val unusable = Ear.entries.filterNot { p.usable(it) }
    if (unusable.isNotEmpty()) {
        Text(
            "${unusable.joinToString(" and ") { if (it == Ear.LEFT) "Left" else "Right" }} side could not hear the " +
                "tones at the ceiling, so no sound is sent there. The forward sensor cannot tell left from right.",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    Text("Run calibration again each time you put the headset on.", style = MaterialTheme.typography.bodyMedium)
    BigButton("Use this profile", {
        onApply()
        DetermineRuntime.calibration(CalibrationAction.Apply)
    })
    BigButton("Discard", onDiscard, primary = false)
}

@Composable
private fun FailedStep(v: CalibrationView, headsetReady: Boolean, onCancel: () -> Unit) {
    Text(
        v.failureReason ?: "Calibration sound could not play. Reconnect the headset and restart calibration.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
    )
    Text("No hearing profile was saved. Previous answers cannot be reused.", style = MaterialTheme.typography.bodyLarge)
    BigButton("Restart calibration", { DetermineRuntime.startCalibration() }, enabled = headsetReady)
    BigButton("Cancel calibration", onCancel, primary = false)
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .border(if (selected) 3.dp else 1.dp, MaterialTheme.colorScheme.outline)
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
    }
}

/** This build has one calibrated output: the phone's Bluetooth bone-conduction headset (Arch §9 "records the device"). */
private const val DEVICE_ID = "bt-bone-conduction-headset"
private const val BALANCE_STEP_DB = 2
/** CalibrationSession clamps balance to ±12 dB. */
private const val BALANCE_RANGE_DB = 12
