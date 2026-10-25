package org.determine.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.swing.Swing
import org.determine.app.net.BoardAddressBook
import org.determine.app.platform.AppPlatform
import org.determine.app.platform.SettingsStore
import org.determine.app.runtime.DetermineSession
import org.determine.app.ui.DetermineApp
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """determine-desktop [options]
  The Determine app on a desktop: shared screens and real wire-v2 engine, one forward ToF zone.
  Java Sound uses system audio (possibly speakers); no Bluetooth headset or physical motor is verified.

  --sim SCENARIO   also run tools/sim/sim_boards.py (both boards simulated) looping SCENARIO;
                   examples: approach_centre, glass_unknown, cue_board_dropout, idle_open_space;
                   see `python3 tools/sim/sim_boards.py --list` for the current list
  --start          start the hazard loop immediately (as if "Start Determine" was pressed)
  --port N         UDP port for the boards (default 47000, as on the phone)
  --repo DIR       repository root (default: found from the working directory)
  --help"""

private data class Options(val sim: String? = null, val start: Boolean = false, val port: Int = BoardAddressBook.PHONE_PORT, val repo: File? = null)

private fun parse(args: Array<String>): Options {
    var o = Options()
    var i = 0
    fun value(): String = args.getOrNull(++i) ?: fail("missing value for ${args[i - 1]}")
    while (i < args.size) {
        when (val a = args[i]) {
            "--sim" -> o = o.copy(sim = value())
            "--start" -> o = o.copy(start = true)
            "--port" -> o = o.copy(port = value().toIntOrNull()?.takeIf { it in 1..65535 } ?: fail("bad port"))
            "--repo" -> o = o.copy(repo = File(value()))
            "--help", "-h" -> { println(USAGE); exitProcess(0) }
            else -> fail("unknown option $a")
        }
        i++
    }
    return o
}

private fun fail(msg: String): Nothing {
    System.err.println("error: $msg\n$USAGE")
    exitProcess(2)
}

private fun findRepo(explicit: File?): File? {
    explicit?.let { return it.takeIf { File(it, "tools/sim/sim_boards.py").isFile } ?: fail("$it has no tools/sim/sim_boards.py") }
    var d: File? = File("").absoluteFile
    while (d != null) {
        if (File(d, "tools/sim/sim_boards.py").isFile) return d
        d = d.parentFile
    }
    return null
}

/**
 * Runs the two-board simulator against this app until the app exits; its lines are forwarded to stdout. The boards
 * listen on the protocol's fixed ports (47001/47002), because the phone always sends there.
 */
private fun startSimulator(repo: File, scenario: String, port: Int): Process {
    val p = ProcessBuilder(
        "python3", "tools/sim/sim_boards.py", "--phone", "127.0.0.1", "--phone-port", port.toString(),
        "--scenario", scenario, "--loop",
    ).directory(repo).redirectErrorStream(true).start()
    Thread({
        // The stream closes when the simulator is stopped on exit.
        try { p.inputStream.bufferedReader().forEachLine { println(it) } } catch (_: java.io.IOException) {}
    }, "sim-output").apply { isDaemon = true }.start()
    return p
}

private class DesktopAppPlatform(override val settings: SettingsStore, private val session: DetermineSession) : AppPlatform {
    override val phoneCameraSupported = false

    @Composable
    override fun rememberStartAction(): () -> Unit = { session.start() }

    override fun stop() = session.stop()

    @Composable
    override fun rememberCameraRequest(onGranted: () -> Unit): () -> Unit = {}

    @Composable
    override fun BackHandler(enabled: Boolean, onBack: () -> Unit) {}
}

fun main(args: Array<String>) {
    val o = parse(args)
    val settings = DesktopSettings()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    val session = DetermineSession(DesktopHostPlatform(), settings, scope, o.port)
    val sim = o.sim?.let { scenario ->
        val repo = findRepo(o.repo) ?: fail("--sim needs the repository (run from inside it, or pass --repo)")
        startSimulator(repo, scenario, o.port)
    }
    Runtime.getRuntime().addShutdownHook(Thread { sim?.destroy() })
    if (o.start) session.start()
    val platform = DesktopAppPlatform(settings, session)
    application {
        Window(
            onCloseRequest = {
                session.stop()
                sim?.destroy()
                exitApplication()
            },
            title = "Determine (desktop test app)",
            state = rememberWindowState(size = DpSize(420.dp, 900.dp)),
        ) {
            DetermineApp(platform)
        }
    }
}
