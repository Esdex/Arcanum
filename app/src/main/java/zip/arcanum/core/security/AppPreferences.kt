package zip.arcanum.core.security

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
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
        val AUTO_LOCK_DELAY_INDEX = intPreferencesKey("auto_lock_delay_index")
        val AUTO_LOCK_DEADLINE_MS = longPreferencesKey("auto_lock_deadline_ms")
        val DEBUG_MODE            = booleanPreferencesKey("debug_mode")
        val THEME_MODE            = stringPreferencesKey("theme_mode")
        val AMOLED_GLASS          = booleanPreferencesKey("amoled_glass")
        val DYNAMIC_COLOR         = booleanPreferencesKey("dynamic_color")
        val SCREEN_CAPTURE_PROT   = booleanPreferencesKey("screen_capture_protection")
        val DISGUISE_PROMPT_SHOWN = booleanPreferencesKey("disguise_prompt_shown")
        val FIRST_LOGIN_DONE      = booleanPreferencesKey("first_login_done")
        val CALCULATOR_ENABLED        = booleanPreferencesKey("calculator_enabled")
        val DISGUISE_ENABLED          = booleanPreferencesKey("disguise_enabled")
        val DISGUISE_PROFILE          = stringPreferencesKey("disguise_profile")
        val CUSTOM_DISGUISE_NAME      = stringPreferencesKey("custom_disguise_name")
        val CUSTOM_DISGUISE_ICON      = stringPreferencesKey("custom_disguise_icon")
        val CUSTOM_DISGUISE_COLOR     = intPreferencesKey("custom_disguise_color")
        val HIDE_FROM_RECENTS         = booleanPreferencesKey("hide_from_recents")
        val BIOMETRIC_UNLOCK_ENABLED  = booleanPreferencesKey("biometric_unlock_enabled")
        val INTRUDER_DETECTION_ENABLED = booleanPreferencesKey("intruder_detection_enabled")
        val SHOW_MOUNT_LOG            = booleanPreferencesKey("show_mount_log")
        val LAST_SEEN_VERSION_CODE    = intPreferencesKey("last_seen_version_code")
        val DELETE_IMPORTED_FILES     = booleanPreferencesKey("delete_imported_files")
        val DELETE_EXPORTED_FILES     = booleanPreferencesKey("delete_exported_files")
    }

    val autoLockEnabled: Flow<Boolean> = context.appPrefsDataStore.data
        .map { it[Keys.AUTO_LOCK] ?: true }

    suspend fun setAutoLock(enabled: Boolean) {
        context.appPrefsDataStore.edit { it[Keys.AUTO_LOCK] = enabled }
    }

    // 0=Immediately 1=30s 2=1m 3=2m 4=5m 5=10m 6=30m 7=1h
    val autoLockDelayIndex: Flow<Int> = context.appPrefsDataStore.data
        .map { it[Keys.AUTO_LOCK_DELAY_INDEX] ?: 2 }

    suspend fun setAutoLockDelayIndex(index: Int) {
        context.appPrefsDataStore.edit { it[Keys.AUTO_LOCK_DELAY_INDEX] = index }
    }

    val autoLockDeadlineMs: Flow<Long> = context.appPrefsDataStore.data
        .map { it[Keys.AUTO_LOCK_DEADLINE_MS] ?: 0L }

    suspend fun setAutoLockDeadlineMs(deadlineMs: Long) {
        context.appPrefsDataStore.edit { prefs ->
            if (deadlineMs > 0L) prefs[Keys.AUTO_LOCK_DEADLINE_MS] = deadlineMs
            else prefs.remove(Keys.AUTO_LOCK_DEADLINE_MS)
        }
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

    val disguisePromptShown: Flow<Boolean> = context.appPrefsDataStore.data
        .map { it[Keys.DISGUISE_PROMPT_SHOWN] ?: false }

    suspend fun setDisguisePromptShown(shown: Boolean) {
        context.appPrefsDataStore.edit { it[Keys.DISGUISE_PROMPT_SHOWN] = shown }
    }

    val firstLoginDone: Flow<Boolean> = context.appPrefsDataStore.data
        .map { it[Keys.FIRST_LOGIN_DONE] ?: false }

    suspend fun setFirstLoginDone() {
        context.appPrefsDataStore.edit { it[Keys.FIRST_LOGIN_DONE] = true }
    }

    // null = key absent (first install); default = true (calculator on)
    val calculatorEnabled: Flow<Boolean?> = context.appPrefsDataStore.data
        .map { it[Keys.CALCULATOR_ENABLED] }

    suspend fun setCalculatorEnabled(enabled: Boolean) {
        context.appPrefsDataStore.edit { it[Keys.CALCULATOR_ENABLED] = enabled }
    }

    val disguiseEnabled: Flow<Boolean> = context.appPrefsDataStore.data
        .map { prefs ->
            prefs[Keys.DISGUISE_ENABLED]
                ?: (prefs[Keys.CALCULATOR_ENABLED] == true && prefs[Keys.DISGUISE_PROMPT_SHOWN] == true)
        }

    val disguiseProfile: Flow<DisguiseProfile> = context.appPrefsDataStore.data
        .map { prefs ->
            DisguiseProfile.fromPrefValue(prefs[Keys.DISGUISE_PROFILE])
        }

    suspend fun setDisguiseEnabled(enabled: Boolean) {
        context.appPrefsDataStore.edit { prefs ->
            prefs[Keys.DISGUISE_ENABLED] = enabled
            prefs[Keys.CALCULATOR_ENABLED] =
                enabled && DisguiseProfile.fromPrefValue(prefs[Keys.DISGUISE_PROFILE]) == DisguiseProfile.CALCULATOR
            if (enabled) prefs[Keys.DISGUISE_PROMPT_SHOWN] = true
        }
    }

    suspend fun setDisguiseProfile(profile: DisguiseProfile) {
        context.appPrefsDataStore.edit { prefs ->
            prefs[Keys.DISGUISE_PROFILE] = profile.prefValue
            prefs[Keys.CALCULATOR_ENABLED] =
                (prefs[Keys.DISGUISE_ENABLED] == true) && profile == DisguiseProfile.CALCULATOR
        }
    }

    val customDisguiseName: Flow<String> = context.appPrefsDataStore.data
        .map { it[Keys.CUSTOM_DISGUISE_NAME] ?: "" }

    suspend fun setCustomDisguiseName(name: String) {
        context.appPrefsDataStore.edit { prefs ->
            prefs[Keys.CUSTOM_DISGUISE_NAME] = name.take(32)
        }
    }

    val customDisguiseIcon: Flow<CustomDisguiseIcon> = context.appPrefsDataStore.data
        .map { prefs ->
            CustomDisguiseIcon.fromPrefValue(prefs[Keys.CUSTOM_DISGUISE_ICON])
        }

    suspend fun setCustomDisguiseIcon(icon: CustomDisguiseIcon) {
        context.appPrefsDataStore.edit { it[Keys.CUSTOM_DISGUISE_ICON] = icon.prefValue }
    }

    val customDisguiseColor: Flow<Int> = context.appPrefsDataStore.data
        .map { it[Keys.CUSTOM_DISGUISE_COLOR] ?: 0xFF546E7A.toInt() }

    suspend fun setCustomDisguiseColor(color: Int) {
        context.appPrefsDataStore.edit { it[Keys.CUSTOM_DISGUISE_COLOR] = color }
    }

    val hideFromRecents: Flow<Boolean> = context.appPrefsDataStore.data
        .map { it[Keys.HIDE_FROM_RECENTS] ?: false }

    suspend fun setHideFromRecents(enabled: Boolean) {
        context.appPrefsDataStore.edit { it[Keys.HIDE_FROM_RECENTS] = enabled }
    }

    val biometricUnlockEnabled: Flow<Boolean> = context.appPrefsDataStore.data
        .map { it[Keys.BIOMETRIC_UNLOCK_ENABLED] ?: false }

    suspend fun setBiometricUnlockEnabled(enabled: Boolean) {
        context.appPrefsDataStore.edit { it[Keys.BIOMETRIC_UNLOCK_ENABLED] = enabled }
    }

    val intruderDetectionEnabled: Flow<Boolean> = context.appPrefsDataStore.data
        .map { it[Keys.INTRUDER_DETECTION_ENABLED] ?: false }

    suspend fun setIntruderDetectionEnabled(enabled: Boolean) {
        context.appPrefsDataStore.edit { it[Keys.INTRUDER_DETECTION_ENABLED] = enabled }
    }

    val showMountLog: Flow<Boolean> = context.appPrefsDataStore.data
        .map { it[Keys.SHOW_MOUNT_LOG] ?: false }

    suspend fun setShowMountLog(enabled: Boolean) {
        context.appPrefsDataStore.edit { it[Keys.SHOW_MOUNT_LOG] = enabled }
    }

    // null = key absent (fresh install — no prior version recorded)
    val lastSeenVersionCode: Flow<Int?> = context.appPrefsDataStore.data
        .map { it[Keys.LAST_SEEN_VERSION_CODE] }

    suspend fun setLastSeenVersionCode(code: Int) {
        context.appPrefsDataStore.edit { it[Keys.LAST_SEEN_VERSION_CODE] = code }
    }

    val deleteImportedFiles: Flow<Boolean> = context.appPrefsDataStore.data
        .map { it[Keys.DELETE_IMPORTED_FILES] ?: false }

    suspend fun setDeleteImportedFiles(enabled: Boolean) {
        context.appPrefsDataStore.edit { it[Keys.DELETE_IMPORTED_FILES] = enabled }
    }

    val deleteExportedFiles: Flow<Boolean> = context.appPrefsDataStore.data
        .map { it[Keys.DELETE_EXPORTED_FILES] ?: false }

    suspend fun setDeleteExportedFiles(enabled: Boolean) {
        context.appPrefsDataStore.edit { it[Keys.DELETE_EXPORTED_FILES] = enabled }
    }

    suspend fun clearAll() {
        context.appPrefsDataStore.edit { it.clear() }
    }
}
