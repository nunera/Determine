package org.determine.android.settings

import org.determine.app.platform.SettingsStore
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.determine.app.describe.DescribeEndpoint
import org.determine.core.hearing.MIN_HAPTIC_MAX
import org.determine.core.FrameSourceKind

/**
 * Android [SettingsStore]: persisted user choices, with the Describe API key in EncryptedSharedPreferences.
 * Spearcons default on; Describe is an explicit opt-in, default off, used only on a user long-press.
 * Changing the camera source restarts the session; the board haptic cap applies live and is never zero.
 */
class UserSettings(context: Context) : SettingsStore {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("determine", Context.MODE_PRIVATE)
    private val secrets: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            app,
            "determine-secrets",
            MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        // Keystore failure (rare, e.g. a broken device keystore): keep the key in app-private storage only.
        Log.w("UserSettings", "encrypted storage unavailable; API key stored in private app preferences", e)
        app.getSharedPreferences("determine-secrets-plain", Context.MODE_PRIVATE)
    }

    private val _spearcons = MutableStateFlow(prefs.getBoolean(KEY_SPEARCONS, true))
    override val spearcons: StateFlow<Boolean> = _spearcons.asStateFlow()

    private val _describeEnabled = MutableStateFlow(prefs.getBoolean(KEY_DESCRIBE, false))
    override val describeEnabled: StateFlow<Boolean> = _describeEnabled.asStateFlow()

    private val _describeUrl = MutableStateFlow(prefs.getString(KEY_DESCRIBE_URL, "") ?: "")
    override val describeUrl: StateFlow<String> = _describeUrl.asStateFlow()

    private val _describeKeySet = MutableStateFlow(!secrets.getString(KEY_DESCRIBE_KEY, null).isNullOrEmpty())
    override val describeKeySet: StateFlow<Boolean> = _describeKeySet.asStateFlow()

    private val _frameSource = MutableStateFlow(enumPref(KEY_FRAME_SOURCE, FrameSourceKind.CAM_BOARD))
    override val frameSource: StateFlow<FrameSourceKind> = _frameSource.asStateFlow()

    /** Board haptic cap sent in PROFILE (1..255). Never 0: the failure channel must survive phone loss. */
    private val _hapticMax = MutableStateFlow(prefs.getInt(KEY_HAPTIC_MAX, 255).coerceIn(MIN_HAPTIC_MAX, 255))
    override val hapticMax: StateFlow<Int> = _hapticMax.asStateFlow()

    override fun setSpearcons(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SPEARCONS, enabled).apply()
        _spearcons.value = enabled
    }

    override fun setDescribeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DESCRIBE, enabled).apply()
        _describeEnabled.value = enabled
    }

    override fun setDescribeUrl(url: String) {
        val v = url.trim()
        prefs.edit().putString(KEY_DESCRIBE_URL, v).apply()
        _describeUrl.value = v
    }

    override fun setDescribeKey(key: String) {
        val v = key.trim()
        secrets.edit().putString(KEY_DESCRIBE_KEY, v).apply()
        _describeKeySet.value = v.isNotEmpty()
    }

    /** Current endpoint, or null if URL or key is missing. Read on the Describe HTTP thread. */
    override fun describeEndpoint(): DescribeEndpoint? {
        val url = _describeUrl.value
        val key = secrets.getString(KEY_DESCRIBE_KEY, null).orEmpty()
        return if (url.isEmpty() || key.isEmpty()) null else DescribeEndpoint(url, key)
    }

    override fun setFrameSource(source: FrameSourceKind) {
        prefs.edit().putString(KEY_FRAME_SOURCE, source.name).apply()
        _frameSource.value = source
    }

    override fun setHapticMax(value: Int) {
        val v = value.coerceIn(MIN_HAPTIC_MAX, 255)
        prefs.edit().putInt(KEY_HAPTIC_MAX, v).apply()
        _hapticMax.value = v
    }

    private inline fun <reified E : Enum<E>> enumPref(key: String, default: E): E =
        prefs.getString(key, null)?.let { s -> enumValues<E>().firstOrNull { it.name == s } } ?: default

    private companion object {
        const val KEY_SPEARCONS = "spearcons"
        const val KEY_DESCRIBE = "describe_enabled"
        const val KEY_DESCRIBE_URL = "describe_url"
        const val KEY_DESCRIBE_KEY = "describe_key"
        const val KEY_FRAME_SOURCE = "frame_source"
        const val KEY_HAPTIC_MAX = "haptic_max"
    }
}
