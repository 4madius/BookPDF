package com.example.bookapp.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Brightness value meaning "don't override, use the phone's own brightness" */
const val SYSTEM_BRIGHTNESS = -1f

data class ReaderSettings(
    val nightMode: Boolean = false,
    val brightness: Float = SYSTEM_BRIGHTNESS,     // 0.05..1.0, or SYSTEM_BRIGHTNESS
    val doublePage: Boolean = false                // false = one page at a time, true = two-page spread
)

// One DataStore file for the whole app. It must be declared once, at the top level.
private val Context.readerDataStore by preferencesDataStore(name = "reader_settings")

class SettingsRepository(context: Context) {
    private val dataStore = context.applicationContext.readerDataStore

    private val nightModeKey = booleanPreferencesKey("night_mode")
    private val brightnessKey = floatPreferencesKey("brightness")
    private val doublePageKey = booleanPreferencesKey("double_page")

    val settings: Flow<ReaderSettings> = dataStore.data.map { prefs ->
        ReaderSettings(
            nightMode = prefs[nightModeKey] ?: false,
            brightness = prefs[brightnessKey] ?: SYSTEM_BRIGHTNESS,
            doublePage = prefs[doublePageKey] ?: false
        )
    }

    suspend fun setNightMode(enabled: Boolean) {
        dataStore.edit { it[nightModeKey] = enabled }
    }

    suspend fun setBrightness(value: Float) {
        dataStore.edit { it[brightnessKey] = value }
    }

    suspend fun setDoublePage(enabled: Boolean) {
        dataStore.edit { it[doublePageKey] = enabled }
    }
}
