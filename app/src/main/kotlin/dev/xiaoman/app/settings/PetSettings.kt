package dev.xiaoman.app.settings

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Where the pet sits on screen (density-independent, so it scales across devices). */
data class PetPosition(val xDp: Float, val yDp: Float)

/** Persists pet preferences. Separate file from [AppearanceSettings] because the values are unrelated. */
class PetSettings(context: Context) {

    private val prefs = context.getSharedPreferences("pet", Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(ENABLED, false))
    val enabled = _enabled.asStateFlow()

    /** Path under [Context.getFilesDir] to the user-uploaded sheet; null → use bundled default. */
    private val _customPath = MutableStateFlow(prefs.getString(CUSTOM_PATH, null))
    val customPath = _customPath.asStateFlow()

    /** Bumped on every upload: the target path never changes, so this is what signals a reload. */
    private val _sheetVersion = MutableStateFlow(prefs.getInt(SHEET_VERSION, 0))
    val sheetVersion = _sheetVersion.asStateFlow()

    private val _position = MutableStateFlow(loadPosition())
    val position = _position.asStateFlow()

    fun setEnabled(on: Boolean) {
        prefs.edit { putBoolean(ENABLED, on) }
        _enabled.value = on
    }

    fun setCustomSheet(file: File?) {
        val path = file?.absolutePath
        // Clean up the previous upload to avoid leaking bytes. Uploads always land on the same target
        // file, so a re-upload passes its own path here - deleting it would wipe the sheet just written.
        val prev = customPath.value
        if (prev != null && prev != path) runCatching { File(prev).delete() }
        val version = _sheetVersion.value + 1
        prefs.edit {
            putString(CUSTOM_PATH, path)
            putInt(SHEET_VERSION, version)
        }
        _customPath.value = path
        _sheetVersion.value = version
    }

    fun setPosition(xDp: Float, yDp: Float) {
        prefs.edit {
            putFloat(POS_X, xDp)
            putFloat(POS_Y, yDp)
        }
        _position.value = PetPosition(xDp, yDp)
    }

    fun clearPosition() {
        prefs.edit { remove(POS_X); remove(POS_Y) }
        _position.value = PetPosition(-1f, -1f)
    }

    private fun loadPosition(): PetPosition {
        val x = prefs.getFloat(POS_X, -1f)
        val y = prefs.getFloat(POS_Y, -1f)
        return PetPosition(x, y)
    }

    companion object {
        private const val ENABLED = "enabled"
        private const val CUSTOM_PATH = "customPath"
        private const val SHEET_VERSION = "sheetVersion"
        private const val POS_X = "posX"
        private const val POS_Y = "posY"
    }
}
