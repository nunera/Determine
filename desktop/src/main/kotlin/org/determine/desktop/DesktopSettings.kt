package org.determine.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.determine.app.describe.DescribeEndpoint
import org.determine.app.platform.SettingsStore
import org.determine.core.hearing.MIN_HAPTIC_MAX
import org.determine.core.FrameSourceKind
import java.util.prefs.Preferences

/**
 * Desktop [SettingsStore] in java.util.prefs (per user, ~/.java/.userPrefs/org/determine/desktop on Linux).
 * Test app: the Describe API key is stored there in plain text, unlike the Android app's encrypted store.
 */
class DesktopSettings(private val prefs: Preferences = Preferences.userRoot().node("org/determine/desktop")) : SettingsStore {
    private val _spearcons = MutableStateFlow(prefs.getBoolean(KEY_SPEARCONS, true))
    override val spearcons: StateFlow<Boolean> = _spearcons.asStateFlow()

    private val _describeEnabled = MutableStateFlow(prefs.getBoolean(KEY_DESCRIBE, false))
    override val describeEnabled: StateFlow<Boolean> = _describeEnabled.asStateFlow()

    private val _describeUrl = MutableStateFlow(prefs.get(KEY_DESCRIBE_URL, ""))
    override val describeUrl: StateFlow<String> = _describeUrl.asStateFlow()

    private val _describeKeySet = MutableStateFlow(prefs.get(KEY_DESCRIBE_KEY, "").isNotEmpty())
    override val describeKeySet: StateFlow<Boolean> = _describeKeySet.asStateFlow()

    private val _frameSource = MutableStateFlow(enumPref(KEY_FRAME_SOURCE, FrameSourceKind.CAM_BOARD))
    override val frameSource: StateFlow<FrameSourceKind> = _frameSource.asStateFlow()

    private val _hapticMax = MutableStateFlow(prefs.getInt(KEY_HAPTIC_MAX, 255).coerceIn(MIN_HAPTIC_MAX, 255))
    override val hapticMax: StateFlow<Int> = _hapticMax.asStateFlow()

    override fun setSpearcons(enabled: Boolean) { prefs.putBoolean(KEY_SPEARCONS, enabled); _spearcons.value = enabled }

    override fun setDescribeEnabled(enabled: Boolean) { prefs.putBoolean(KEY_DESCRIBE, enabled); _describeEnabled.value = enabled }

    override fun setDescribeUrl(url: String) {
        val v = url.trim()
        prefs.put(KEY_DESCRIBE_URL, v)
        _describeUrl.value = v
    }

    override fun setDescribeKey(key: String) {
        val v = key.trim()
        prefs.put(KEY_DESCRIBE_KEY, v)
        _describeKeySet.value = v.isNotEmpty()
    }

    override fun describeEndpoint(): DescribeEndpoint? {
        val url = _describeUrl.value
        val key = prefs.get(KEY_DESCRIBE_KEY, "")
        return if (url.isEmpty() || key.isEmpty()) null else DescribeEndpoint(url, key)
    }

    override fun setFrameSource(source: FrameSourceKind) { prefs.put(KEY_FRAME_SOURCE, source.name); _frameSource.value = source }

    override fun setHapticMax(value: Int) {
        val v = value.coerceIn(MIN_HAPTIC_MAX, 255)
        prefs.putInt(KEY_HAPTIC_MAX, v)
        _hapticMax.value = v
    }

    private inline fun <reified E : Enum<E>> enumPref(key: String, default: E): E =
        prefs.get(key, null)?.let { s -> enumValues<E>().firstOrNull { it.name == s } } ?: default

    private companion object {
        const val KEY_SPEARCONS = "spearcons"
        const val KEY_DESCRIBE = "describe_enabled"
        const val KEY_DESCRIBE_URL = "describe_url"
        const val KEY_DESCRIBE_KEY = "describe_key"
        const val KEY_FRAME_SOURCE = "frame_source"
        const val KEY_HAPTIC_MAX = "haptic_max"
    }
}
