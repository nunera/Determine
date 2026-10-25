package org.determine.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.determine.app.net.BoardAddressBook
import org.determine.app.runtime.DetermineRuntime
import org.determine.core.hearing.HearingMode
import java.net.Inet4Address
import java.net.NetworkInterface
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import org.determine.app.platform.LocalPlatform
import androidx.compose.material3.Slider
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import org.determine.core.hearing.MIN_HAPTIC_MAX
import kotlin.math.roundToInt
import org.determine.core.FrameSourceKind

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val platform = LocalPlatform.current
    val settings = platform.settings
    val spearcons by settings.spearcons.collectAsStateWithLifecycle()
    val running by DetermineRuntime.running.collectAsStateWithLifecycle()
    val link by DetermineRuntime.link.collectAsStateWithLifecycle()
    val profile by DetermineRuntime.profile.collectAsStateWithLifecycle()
    val addresses = remember { localIpv4Addresses() }
    val describeEnabled by settings.describeEnabled.collectAsStateWithLifecycle()
    val describeUrl by settings.describeUrl.collectAsStateWithLifecycle()
    val keySet by settings.describeKeySet.collectAsStateWithLifecycle()
    val frameSource by settings.frameSource.collectAsStateWithLifecycle()
    val hapticMax by settings.hapticMax.collectAsStateWithLifecycle()
    val headset by DetermineRuntime.headset.collectAsStateWithLifecycle()
    val fallbacks by DetermineRuntime.fallbacks.collectAsStateWithLifecycle()
    var urlDraft by remember(describeUrl) { mutableStateOf(describeUrl) }
    var keyDraft by remember { mutableStateOf("") }
    val requestCamera = platform.rememberCameraRequest { settings.setFrameSource(FrameSourceKind.PHONE_CAMERA) }

    Screen(title = "Settings", onBack = onBack) {
        SectionHeading("Spoken words")
        ToggleRow(
            label = "Spearcons: quick spoken word after each warning sound",
            checked = spearcons,
            enabled = true,
            onChange = settings::setSpearcons,
        )

        SectionHeading("Output mode")
        if (running && profile != null) {
            Text("Switch live, for example to one-ear mode. Levels stay as calibrated and under the ceiling.",
                style = MaterialTheme.typography.bodyMedium)
            Column(Modifier.selectableGroup()) {
                HearingMode.entries.forEach { mode ->
                    val selected = profile?.mode == mode
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .selectable(selected = selected, role = Role.RadioButton) { DetermineRuntime.setMode(mode) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Text(modeName(mode), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        } else {
            Text("Start Determine to change the output mode.", style = MaterialTheme.typography.bodyMedium)
        }

        SectionHeading("Headset audio")
        Text(
            if (running && headset == null) "Desktop sounds use the system audio device (possibly speakers), not a verified " +
                "Bluetooth headset. This test app cannot verify wearable audio or physical temple vibration."
            else "Every warning sound plays on your Bluetooth bone-conduction headset. If it is not connected, Determine " +
                "reports system off and stays silent on the phone speaker. With warning sounds off (haptic only) no " +
                "headset is needed; calibration tones always need it.",
            style = MaterialTheme.typography.bodyMedium,
        )
        InfoLine(
            "Headset",
            when {
                !running -> "stopped"
                profile?.mode == HearingMode.HAPTIC_ONLY -> "not required: warning sounds are off"
                headset == null -> "not verified on this platform (desktop Java Sound)"
                headset?.routeReady == true -> "ready: ${headset?.name ?: "connected"}"
                headset?.present == true -> "found, audio route not ready"
                else -> "NOT CONNECTED"
            },
        )
        fallbacks?.phoneAudioError?.let { InfoLine("Local audio error", it) }

        SectionHeading("Temple vibration strength")
        var strength by remember(hapticMax) { mutableStateOf(hapticMax.toFloat()) }
        val strengthText = "$hapticMax of 255"
        Text(
            "Strongest vibration the glasses temples may use for warnings. It cannot be turned off: if the phone " +
                "connection is lost the temples are the only alert left. The weakest setting ($MIN_HAPTIC_MAX) is not a " +
                "tested guarantee that you will feel it, so pick a strength you can feel reliably.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("Strength: $strengthText", style = MaterialTheme.typography.titleMedium)
        Slider(
            value = strength,
            onValueChange = { strength = it },
            onValueChangeFinished = {
                val v = strength.roundToInt().coerceIn(MIN_HAPTIC_MAX, 255)
                settings.setHapticMax(v)
            },
            valueRange = MIN_HAPTIC_MAX.toFloat()..255f,
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "Temple vibration strength"
                stateDescription = strengthText
            },
        )

        SectionHeading("Describe (what's ahead)")
        Text(
            "Only when you long-press \"What's ahead?\": the phone blurs faces in one camera frame, sends it to the " +
                "Determine cloud (a small vision model on AWS Inferentia2), and reads back two sentences. Face " +
                "blurring is imperfect and can miss small or distant faces. Images are not " +
                "stored. Warnings never wait for it, and any close obstacle interrupts it.",
            style = MaterialTheme.typography.bodyMedium,
        )
        ToggleRow(
            label = "Scene description on request",
            checked = describeEnabled,
            enabled = true,
            onChange = settings::setDescribeEnabled,
        )
        OutlinedTextField(
            value = urlDraft,
            onValueChange = { urlDraft = it },
            label = { Text("Describe service URL") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = keyDraft,
            onValueChange = { keyDraft = it },
            label = { Text(if (keySet) "API key (saved; type to replace)" else "API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        BigButton("Save Describe service", onClick = {
            settings.setDescribeUrl(urlDraft)
            if (keyDraft.isNotEmpty()) settings.setDescribeKey(keyDraft)
            keyDraft = ""
        })

        SectionHeading("Camera fallback (software demo, not the proposed glasses)")
        ChoiceGroup(
            title = "Camera",
            options = listOf(
                FrameSourceKind.CAM_BOARD to "Glasses camera board",
                FrameSourceKind.PHONE_CAMERA to "Phone camera on a headband",
            ),
            selected = frameSource,
            onSelect = { choice ->
                if (choice == FrameSourceKind.PHONE_CAMERA) requestCamera() else settings.setFrameSource(choice)
            },
            unavailable = if (platform.phoneCameraSupported) emptySet() else setOf(FrameSourceKind.PHONE_CAMERA),
        )
        Text("Changing the camera restarts Determine. The forward distance sensor on the cue board is always required.",
            style = MaterialTheme.typography.bodyMedium)

        SectionHeading("Hotspot and boards")
        Text(
            "Turn on this phone's Wi-Fi hotspot with the name and password flashed into both boards. The boards " +
                "send to the hotspot gateway on port ${BoardAddressBook.PHONE_PORT}.",
            style = MaterialTheme.typography.bodyMedium,
        )
        InfoLine("This phone's addresses", addresses.ifEmpty { listOf("none") }.joinToString(", "))
        InfoLine("Listening", if (!running) "stopped" else if (link?.bound == false) "FAILED: ${link?.bindError}" else "UDP ${BoardAddressBook.PHONE_PORT}")
        InfoLine("Camera board", link?.camIp?.let { "$it, port ${BoardAddressBook.CAM_PORT}" } ?: "not heard")
        InfoLine("Cue board", link?.cueIp?.let { "$it, port ${BoardAddressBook.CUE_PORT}" } ?: "not heard")
        Text("The front LED on the camera board is lit whenever it streams.", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .then(if (enabled) Modifier else Modifier.semantics { disabled() }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun <T> ChoiceGroup(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    unavailable: Set<T> = emptySet(),
) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Column(Modifier.selectableGroup()) {
        options.forEach { (value, label) ->
            val enabled = value !in unavailable
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .selectable(selected = value == selected, enabled = enabled, role = Role.RadioButton) { onSelect(value) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = value == selected, onClick = null, enabled = enabled)
                Text(
                    if (enabled) label else "$label (not on this device)",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

/** IPv4 addresses of this phone's up interfaces; the hotspot's is the boards' gateway. */
private fun localIpv4Addresses(): List<String> = try {
    NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { "${it.hostAddress} (${nif.name})" } }
} catch (_: Exception) {
    emptyList()
}
