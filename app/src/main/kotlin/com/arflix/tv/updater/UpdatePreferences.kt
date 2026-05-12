package com.arflix.tv.updater

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.arflix.tv.util.settingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class AppUpdateInstallPhase {
    IDLE,
    INSTALLING,
    PENDING_USER_ACTION,
    SUCCESS,
    FAILED
}

data class AppUpdateInstallStatus(
    val phase: AppUpdateInstallPhase,
    val message: String?
)

@Singleton
class UpdatePreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val ignoredTagKey = stringPreferencesKey("app_update_ignored_release_tag")
    private val lastCheckAtKey = longPreferencesKey("app_update_last_check_at_ms")
    private val installPhaseKey = stringPreferencesKey("app_update_install_phase")
    private val installMessageKey = stringPreferencesKey("app_update_install_message")

    val ignoredTag: Flow<String?> = context.settingsDataStore.data.map { prefs -> prefs[ignoredTagKey] }
    val lastCheckAtMs: Flow<Long> = context.settingsDataStore.data.map { prefs -> prefs[lastCheckAtKey] ?: 0L }
    val installStatus: Flow<AppUpdateInstallStatus> = context.settingsDataStore.data.map { prefs ->
        val phase = prefs[installPhaseKey]
            ?.let { raw -> runCatching { AppUpdateInstallPhase.valueOf(raw) }.getOrNull() }
            ?: AppUpdateInstallPhase.IDLE
        AppUpdateInstallStatus(
            phase = phase,
            message = prefs[installMessageKey]
        )
    }

    suspend fun setIgnoredTag(tag: String?) {
        context.settingsDataStore.edit { prefs ->
            if (tag == null) prefs.remove(ignoredTagKey) else prefs[ignoredTagKey] = tag
        }
    }

    suspend fun setLastCheckAtMs(value: Long) {
        context.settingsDataStore.edit { prefs ->
            prefs[lastCheckAtKey] = value
        }
    }

    suspend fun setInstallStatus(phase: AppUpdateInstallPhase, message: String? = null) {
        context.settingsDataStore.edit { prefs ->
            prefs[installPhaseKey] = phase.name
            if (message.isNullOrBlank()) {
                prefs.remove(installMessageKey)
            } else {
                prefs[installMessageKey] = message
            }
        }
    }

    suspend fun clearInstallStatus() {
        setInstallStatus(AppUpdateInstallPhase.IDLE, null)
    }
}
