package org.determine.android.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.determine.android.describe.DescribeEndpoint
import org.determine.core.CueOutput
import org.determine.core.FrameSourceKind

/**
 * Persisted user choices.
 *  - Spearcons default ON (Arch §8.2: on by default during training, opt-in once the wearer is fluent).
 *  - Describe (Arch §7.3) is an explicit opt-in, default OFF; it is only ever used on a user long-press and is
 *    never read by the hazard loop. The API key is kept in EncryptedSharedPreferences.
 *  - Frame source and cue output fallbacks (Arch §11.4 demo branches) default to the proposed glasses; changing
 *    either restarts the engine session.
 */
class UserSettings(context: Context) {
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
    val spearcons: StateFlow<Boolean> = _spearcons.asStateFlow()

    private val _describeEnabled = MutableStateFlow(prefs.getBoolean(KEY_DESCRIBE, false))
    val describeEnabled: StateFlow<Boolean> = _describeEnabled.asStateFlow()

    private val _describeUrl = MutableStateFlow(prefs.getString(KEY_DESCRIBE_URL, "") ?: "")
    val describeUrl: StateFlow<String> = _describeUrl.asStateFlow()

    private val _describeKeySet = MutableStateFlow(!secrets.getString(KEY_DESCRIBE_KEY, null).isNullOrEmpty())
    val describeKeySet: StateFlow<Boolean> = _describeKeySet.asStateFlow()

    private val _frameSource = MutableStateFlow(enumPref(KEY_FRAME_SOURCE, FrameSourceKind.CAM_BOARD))
    val frameSource: StateFlow<FrameSourceKind> = _frameSource.asStateFlow()

    private val _cueOutput = MutableStateFlow(enumPref(KEY_CUE_OUTPUT, CueOutput.GLASSES))
    val cueOutput: StateFlow<CueOutput> = _cueOutput.asStateFlow()

    fun setSpearcons(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SPEARCONS, enabled).apply()
        _spearcons.value = enabled
    }

    fun setDescribeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DESCRIBE, enabled).apply()
        _describeEnabled.value = enabled
    }

    fun setDescribeUrl(url: String) {
        val v = url.trim()
        prefs.edit().putString(KEY_DESCRIBE_URL, v).apply()
        _describeUrl.value = v
    }

    fun setDescribeKey(key: String) {
        val v = key.trim()
        secrets.edit().putString(KEY_DESCRIBE_KEY, v).apply()
        _describeKeySet.value = v.isNotEmpty()
    }

    /** Current endpoint, or null if URL or key is missing. Read on the Describe HTTP thread. */
    fun describeEndpoint(): DescribeEndpoint? {
        val url = _describeUrl.value
        val key = secrets.getString(KEY_DESCRIBE_KEY, null).orEmpty()
        return if (url.isEmpty() || key.isEmpty()) null else DescribeEndpoint(url, key)
    }

    fun setFrameSource(source: FrameSourceKind) {
        prefs.edit().putString(KEY_FRAME_SOURCE, source.name).apply()
        _frameSource.value = source
    }

    fun setCueOutput(output: CueOutput) {
        prefs.edit().putString(KEY_CUE_OUTPUT, output.name).apply()
        _cueOutput.value = output
    }

    private inline fun <reified E : Enum<E>> enumPref(key: String, default: E): E =
        prefs.getString(key, null)?.let { s -> enumValues<E>().firstOrNull { it.name == s } } ?: default

    private companion object {
        const val KEY_SPEARCONS = "spearcons"
        const val KEY_DESCRIBE = "describe_enabled"
        const val KEY_DESCRIBE_URL = "describe_url"
        const val KEY_DESCRIBE_KEY = "describe_key"
        const val KEY_FRAME_SOURCE = "frame_source"
        const val KEY_CUE_OUTPUT = "cue_output"
    }
}
