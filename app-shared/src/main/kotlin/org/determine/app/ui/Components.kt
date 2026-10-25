package org.determine.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.determine.core.Alert
import org.determine.core.Band
import org.determine.core.SystemState

/** Spoken/visible description of a state (also used for the notification). There is no "clear" state. */
fun describe(state: SystemState?): String = when (state) {
    null -> "Stopped: no warnings"
    SystemState.STARTING -> "Starting: waiting for both boards"
    SystemState.READY -> "Ready"
    SystemState.DEGRADED -> "Limited: distance sensor not fully usable"
    SystemState.UNAVAILABLE -> "System off: warnings unavailable"
    SystemState.PAUSED -> "Paused: no warnings"
}

/** "Obstacle ahead, near, 0.4 metres". Distance only when backed by a valid forward ToF reading (Arch §7.2). */
fun describe(alert: Alert): String {
    val band = when (alert.band) {
        Band.NEAR -> "near"
        Band.MID -> "mid"
        Band.FAR -> "far"
        Band.NONE -> null
    }
    val dist = alert.distanceMm?.let { "%.1f metres".format(it / 1000.0) }
    return listOfNotNull("Obstacle ahead", band, dist).joinToString(", ")
}

/** PROTOCOL.md BEAT fault bits. Bit 2 is reserved since v2 (the board's I2S audio was removed). */
val FAULT_NAMES = listOf(
    "sensor init failed", "sensor read error", "reserved", "haptic motor error", "second haptic field error (one motor)",
    "brownout seen", "no clock sync", "watchdog active", "low heap", "capture failed",
)

fun faultText(faults: Int): String =
    FAULT_NAMES.filterIndexed { bit, _ -> faults and (1 shl bit) != 0 }.joinToString(", ").ifEmpty { "none" }

/** Full-width button at least 72 dp tall: easy to find by touch and with TalkBack explore-by-touch. */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = true,
) {
    val m = modifier.fillMaxWidth().heightIn(min = 72.dp)
    val shape = RoundedCornerShape(6.dp)
    if (primary) {
        Button(onClick = onClick, modifier = m, enabled = enabled, shape = shape, contentPadding = PaddingValues(16.dp)) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    } else {
        OutlinedButton(
            onClick = onClick, modifier = m, enabled = enabled, shape = shape,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
            contentPadding = PaddingValues(16.dp),
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun SectionHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
    )
}

private val BackArrow: ImageVector = ImageVector.Builder(
    name = "Back", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
).apply {
    path(fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black)) {
        moveTo(20f, 11f)
        horizontalLineTo(7.83f)
        lineToRelative(5.59f, -5.59f)
        lineTo(12f, 4f)
        lineToRelative(-8f, 8f)
        lineToRelative(8f, 8f)
        lineToRelative(1.41f, -1.41f)
        lineTo(7.83f, 13f)
        horizontalLineTo(20f)
        close()
    }
}.build()

/** Screen frame: heading title (a TalkBack heading), labelled back button, scrollable column. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Screen(title: String, onBack: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(BackArrow, contentDescription = "Back") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}
