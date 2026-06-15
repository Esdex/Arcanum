package zip.arcanum.core.security

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import zip.arcanum.core.theme.ThemeMode
import javax.inject.Inject
import javax.inject.Singleton

private val Context.appPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_preferences")

@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val AUTO_LOCK             = booleanPreferencesKey("auto_lock")
        val DEBUG_MODE            = booleanPreferencesKey("debug_mode")
        val THEME_MODE            = stringPreferencesKey("theme_mode")
        val AMOLED_GLASS          = booleanPreferencesKey("amoled_glass")
        val DYNAMIC_COLOR         = booleanPreferencesKey("dynamic_color")
        val SCREEN_CAPTURE_PROT   = booleanPreferencesKey("screen_capture_protection")
    }

    val autoLockEnabled: Flow<Boolean> = context.appPrefsDataStore.data
        .map { it[Keys.AUTO_LOCK] ?: true }

    suspend fun setAutoLock(enabled: Boolean) {
        context.appPrefsDataStore.edit { it[Keys.AUTO_LOCK] = enabled }
    }

    val debugMode: Flow<Boolean> = context.appPrefsDataStore.data
        .map { it[Keys.DEBUG_MODE] ?: false }

    suspend fun setDebugMode(enabled: Boolean) {
        context.appPrefsDataStore.edit { it[Keys.DEBUG_MODE] = enabled }
    }

    val themeMode: Flow<ThemeMode> = context.appPrefsDataStore.data
        .map { prefs ->
            prefs[Keys.THEME_MODE]
                ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM
        }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.appPrefsDataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    val isAmoledGlass: Flow<Boolean> = context.appPrefsDataStore.data
        .map { it[Keys.AMOLED_GLASS] ?: false }

    suspend fun setAmoledGlass(enabled: Boolean) {
        context.appPrefsDataStore.edit { it[Keys.AMOLED_GLASS] = enabled }
    }

    val isDynamicColor: Flow<Boolean> = context.appPrefsDataStore.data
        .map { it[Keys.DYNAMIC_COLOR] ?: true }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.appPrefsDataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    val screenCaptureProtection: Flow<Boolean> = context.appPrefsDataStore.data
        .map { it[Keys.SCREEN_CAPTURE_PROT] ?: true }

    suspend fun setScreenCaptureProtection(enabled: Boolean) {
        context.appPrefsDataStore.edit { it[Keys.SCREEN_CAPTURE_PROT] = enabled }
    }
}
