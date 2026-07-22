package com.markusmaribu.picochat.state

import android.content.Context
import android.content.SharedPreferences
import com.markusmaribu.picochat.util.ThemeColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single owner of the `picochat_prefs` SharedPreferences, exposing every
 * setting as a [StateFlow] so all screens (and both display windows) observe
 * the same values. Replaces the Intent-extra plumbing between the old
 * activities.
 */
class SettingsRepository private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("picochat_prefs", Context.MODE_PRIVATE)

    private val _username = MutableStateFlow(
        prefs.getString(KEY_USERNAME, DEFAULT_USERNAME) ?: DEFAULT_USERNAME
    )
    val username: StateFlow<String> = _username.asStateFlow()

    private val _colorIndex = MutableStateFlow(loadColorIndex())
    val colorIndex: StateFlow<Int> = _colorIndex.asStateFlow()

    private val _viewsSwapped = MutableStateFlow(prefs.getBoolean(KEY_VIEWS_SWAPPED, false))
    val viewsSwapped: StateFlow<Boolean> = _viewsSwapped.asStateFlow()

    private val _rotationLocked = MutableStateFlow(prefs.getBoolean(KEY_ROTATION_LOCKED, false))
    val rotationLocked: StateFlow<Boolean> = _rotationLocked.asStateFlow()

    private val _lockedOrientation = MutableStateFlow(prefs.getInt(KEY_LOCKED_ORIENTATION, -1))
    val lockedOrientation: StateFlow<Int> = _lockedOrientation.asStateFlow()

    private val _exportedHashes = MutableStateFlow<Set<String>>(
        prefs.getStringSet(KEY_EXPORTED_HASHES, emptySet()) ?: emptySet()
    )
    val exportedHashes: StateFlow<Set<String>> = _exportedHashes.asStateFlow()

    private val _topScreenSizeIndex = MutableStateFlow(
        TopScreenSize.indexCoerced(prefs.getInt(KEY_TOP_SCREEN_SIZE_INDEX, 0))
    )
    val topScreenSizeIndex: StateFlow<Int> = _topScreenSizeIndex.asStateFlow()

    private val _topScreenAlignmentIndex = MutableStateFlow(
        TopScreenAlignment.indexCoerced(
            prefs.getInt(KEY_TOP_SCREEN_ALIGNMENT_INDEX, TopScreenAlignment.DEFAULT_INDEX)
        )
    )
    val topScreenAlignmentIndex: StateFlow<Int> = _topScreenAlignmentIndex.asStateFlow()

    private fun loadColorIndex(): Int {
        val stored = prefs.getInt(KEY_COLOR_INDEX, ThemeColors.DEFAULT_INDEX)
        if (stored !in ThemeColors.PALETTE.indices) {
            prefs.edit().putInt(KEY_COLOR_INDEX, ThemeColors.DEFAULT_INDEX).apply()
            return ThemeColors.DEFAULT_INDEX
        }
        return stored
    }

    fun setUsername(value: String) {
        _username.value = value
        prefs.edit().putString(KEY_USERNAME, value).apply()
    }

    fun setColorIndex(value: Int) {
        _colorIndex.value = value
        prefs.edit().putInt(KEY_COLOR_INDEX, value).apply()
    }

    fun setViewsSwapped(value: Boolean) {
        _viewsSwapped.value = value
        prefs.edit().putBoolean(KEY_VIEWS_SWAPPED, value).apply()
    }

    fun setRotationLock(locked: Boolean, orientation: Int = -1) {
        _rotationLocked.value = locked
        _lockedOrientation.value = if (locked) orientation else -1
        prefs.edit()
            .putBoolean(KEY_ROTATION_LOCKED, locked)
            .apply {
                if (locked) putInt(KEY_LOCKED_ORIENTATION, orientation)
                else remove(KEY_LOCKED_ORIENTATION)
            }
            .apply()
    }

    fun addExportedHashes(hashes: Collection<String>) {
        val updated = _exportedHashes.value + hashes
        _exportedHashes.value = updated
        prefs.edit().putStringSet(KEY_EXPORTED_HASHES, updated).apply()
    }

    fun cycleTopScreenSizeIndex() {
        val next = TopScreenSize.nextIndex(_topScreenSizeIndex.value)
        _topScreenSizeIndex.value = next
        prefs.edit().putInt(KEY_TOP_SCREEN_SIZE_INDEX, next).apply()
    }

    fun resetTopScreenSizeToFull() {
        if (_topScreenSizeIndex.value == 0) return
        _topScreenSizeIndex.value = 0
        prefs.edit().putInt(KEY_TOP_SCREEN_SIZE_INDEX, 0).apply()
    }

    fun cycleTopScreenAlignmentIndex() {
        val next = TopScreenAlignment.nextIndex(_topScreenAlignmentIndex.value)
        _topScreenAlignmentIndex.value = next
        prefs.edit().putInt(KEY_TOP_SCREEN_ALIGNMENT_INDEX, next).apply()
    }

    fun resetTopScreenAlignmentToCenter() {
        if (_topScreenAlignmentIndex.value == TopScreenAlignment.DEFAULT_INDEX) return
        _topScreenAlignmentIndex.value = TopScreenAlignment.DEFAULT_INDEX
        prefs.edit()
            .putInt(KEY_TOP_SCREEN_ALIGNMENT_INDEX, TopScreenAlignment.DEFAULT_INDEX)
            .apply()
    }

    companion object {
        private const val KEY_USERNAME = "username"
        private const val KEY_COLOR_INDEX = "theme_color_index"
        private const val KEY_VIEWS_SWAPPED = "views_swapped"
        private const val KEY_ROTATION_LOCKED = "rotation_locked"
        private const val KEY_LOCKED_ORIENTATION = "locked_orientation"
        private const val KEY_EXPORTED_HASHES = "exported_hashes"
        private const val KEY_TOP_SCREEN_SIZE_INDEX = "top_screen_size_index"
        private const val KEY_TOP_SCREEN_ALIGNMENT_INDEX = "top_screen_alignment_index"
        private const val DEFAULT_USERNAME = "Player"

        @Volatile
        private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
    }
}
