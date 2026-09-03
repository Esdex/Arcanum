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
import zip.arcanum.core.navigation_components.DefaultContainerTab
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
        val DEBUG_MODE            = booleanPreferencesKey("debug_mode")
        val THEME_MODE            = stringPreferencesKey("theme_mode")
        val AMOLED_GLASS          = booleanPreferencesKey("amoled_glass")
        val DYNAMIC_COLOR         = booleanPreferencesKey("dynamic_color")
        val SCREEN_CAPTURE_PROT   = booleanPreferencesKey("screen_capture_protection")
        val DISGUISE_PROMPT_SHOWN = booleanPreferencesKey("disguise_prompt_shown")
        val MEDIA_LOC_PROMPT_SHOWN = booleanPreferencesKey("media_location_prompt_shown")
        val GALLERY_SORT_BY   = stringPreferencesKey("gallery_sort_by")
        val GALLERY_SORT_ASC  = booleanPreferencesKey("gallery_sort_asc")
        val GALLERY_RANDOM_SEED = longPreferencesKey("gallery_random_seed")
        /**
         * A key that did not exist before is absent for everyone who updates, so they see
         * the hint once for the same reason a fresh install does - which is exactly who
         * should see it, since the mount screen's unlock control moved.
         */
        val MOUNT_HINT_SHOWN      = booleanPreferencesKey("mount_hint_shown")
        val FIRST_LOGIN_DONE      = booleanPreferencesKey("first_login_done")
        val CALCULATOR_ENABLED        = booleanPreferencesKey("calculator_enabled")
        val BIOMETRIC_UNLOCK_ENABLED  = booleanPreferencesKey("biometric_unlock_enabled")
        val SHOW_MOUNT_LOG            = booleanPreferencesKey("show_mount_log")
        val SAVE_MOUNT_LOG           = booleanPreferencesKey("save_mount_log")
        val LAST_SEEN_VERSION_CODE    = intPreferencesKey("last_seen_version_code")
        val UNMOUNT_ON_AUTO_LOCK      = booleanPreferencesKey("unmount_on_auto_lock")
        val GALLERY_RESYNC_BUTTON     = booleanPreferencesKey("gallery_resync_button")
        val DEFAULT_CONTAINER_TAB     = stringPreferencesKey("default_container_tab")
        val RECEIVE_SHARES            = booleanPreferencesKey("receive_shares")
        val FIRST_SEEN_AT             = longPreferencesKey("first_seen_at")
        val LAST_SUPPORT_PROMPT_AT    = longPreferencesKey("last_support_prompt_at")
        val MEDIA_SESSION_CONTENT     = booleanPreferencesKey("media_session_content")
        val ARGON2_OFFER              = booleanPreferencesKey("argon2_offer")

        /** Survives panic mode's "Clear app settings" - see [clearSettingsForPanic]. */
        val PANIC_KEEP: List<Preferences.Key<*>> = listOf(
            CALCULATOR_ENABLED, DISGUISE_PROMPT_SHOWN, RECEIVE_SHARES,
            THEME_MODE, AMOLED_GLASS, DYNAMIC_COLOR,
            FIRST_LOGIN_DONE, MOUNT_HINT_SHOWN, MEDIA_LOC_PROMPT_SHOWN,
            LAST_SEEN_VERSION_CODE, FIRST_SEEN_AT, LAST_SUPPORT_PROMPT_AT
        )
    }

    /**
     * Wipes the app's settings, for panic mode's "Clear app settings" (#134).
     *
     * Everything in [PANIC_KEEP] is carried over, and the reason is the whole point of a
     * panic wipe: what the person opposite you notices is not what was removed but that
     * something changed. An app that looked one way this morning and another way now is the
     * first thing to ask about.
     *
     * Three kinds of thing are kept:
     *
     * - **the two preferences that are paired with a component alias.** Neither the launcher
     *   icon nor the entry in the system share sheet comes back with a preference: clearing
     *   [Keys.CALCULATOR_ENABLED] would leave an icon that says Calculator over an app that
     *   opens at a PIN screen, and clearing [Keys.RECEIVE_SHARES] would leave the app in
     *   every share sheet while its own setting said it was not.
     * - **the way it looks.** A dark, AMOLED app that comes back on the system theme has
     *   announced itself, which is what Esdex reported: "иначе это сразу становится главным
     *   подозрением".
     * - **what has already been seen.** Clear these and the app behaves like a fresh
     *   install: it offers the camouflage it is already wearing, shows the mount hint again,
     *   and puts up "what's new" for a version that was installed weeks ago.
     *
     * Anything added to [Keys] later is cleared unless it is put on this list. That is the
     * safer default for a wipe - but if resetting it would SHOW, it belongs here.
     */
    suspend fun clearSettingsForPanic() {
        val before = context.appPrefsDataStore.data.first()
        context.appPrefsDataStore.edit { prefs ->
            prefs.clear()
            Keys.PANIC_KEEP.forEach { key ->
                @Suppress("UNCHECKED_CAST")
                val typed = key as Preferences.Key<Any>
                before[typed]?.let { prefs[typed] = it }
            }
        }
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

    /**
     * Whether the explanation shown before the ACCESS_MEDIA_LOCATION request has been
     * through once. A denial sets USER_FIXED on the permission, so the system dialog never
     * appears again - explaining a dialog that will not come would only be confusing (#149).
     */
    val mediaLocationPromptShown: Flow<Boolean> = context.appPrefsDataStore.data
        .map { it[Keys.MEDIA_LOC_PROMPT_SHOWN] ?: false }

    suspend fun setMediaLocationPromptShown(shown: Boolean) {
        context.appPrefsDataStore.edit { it[Keys.MEDIA_LOC_PROMPT_SHOWN] = shown }
    }

    /**
     * How the Gallery is sorted. Kept apart from the Files browser's own setting: the two
     * are different lists with different natural orders, and the swipe in the viewer follows
     * whichever list the file was opened from (#151, #122). Date descending is the default,
     * which is the timeline the Gallery has always shown.
     */
    val gallerySortBy: Flow<String> = context.appPrefsDataStore.data
        .map { it[Keys.GALLERY_SORT_BY] ?: "DATE" }

    val gallerySortAscending: Flow<Boolean> = context.appPrefsDataStore.data
        .map { it[Keys.GALLERY_SORT_ASC] ?: false }

    suspend fun setGallerySort(sortBy: String, ascending: Boolean) {
        context.appPrefsDataStore.edit {
            it[Keys.GALLERY_SORT_BY]  = sortBy
            it[Keys.GALLERY_SORT_ASC] = ascending
        }
    }

    /**
     * The seed behind the random order. Stored rather than held in memory because two screens
     * shuffle the same media and have to land on the same arrangement: the grid, and the
     * swipe between files opened from it (#122).
     */
    val galleryRandomSeed: Flow<Long> = context.appPrefsDataStore.data
        .map { it[Keys.GALLERY_RANDOM_SEED] ?: 1L }

    suspend fun setGalleryRandomSeed(seed: Long) {
        context.appPrefsDataStore.edit { it[Keys.GALLERY_RANDOM_SEED] = seed }
    }

    val mountHintShown: Flow<Boolean> = context.appPrefsDataStore.data
        .map { it[Keys.MOUNT_HINT_SHOWN] ?: false }

    suspend fun setMountHintShown() {
        context.appPrefsDataStore.edit { it[Keys.MOUNT_HINT_SHOWN] = true }
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

    val biometricUnlockEnabled: Flow<Boolean> = context.appPrefsDataStore.data
        .map { it[Keys.BIOMETRIC_UNLOCK_ENABLED] ?: false }

    suspend fun setBiometricUnlockEnabled(enabled: Boolean) {
        context.appPrefsDataStore.edit { it[Keys.BIOMETRIC_UNLOCK_ENABLED] = enabled }
    }

    val showMountLog: Flow<Boolean> = context.appPrefsDataStore.data
        .map { it[Keys.SHOW_MOUNT_LOG] ?: false }

    suspend fun setShowMountLog(enabled: Boolean) {
        context.appPrefsDataStore.edit { it[Keys.SHOW_MOUNT_LOG] = enabled }
    }

    val saveMountLog: Flow<Boolean> = context.appPrefsDataStore.data
        .map { it[Keys.SAVE_MOUNT_LOG] ?: false }

    suspend fun setSaveMountLog(enabled: Boolean) {
        context.appPrefsDataStore.edit { it[Keys.SAVE_MOUNT_LOG] = enabled }
    }

    // null = key absent (fresh install — no prior version recorded)
    val lastSeenVersionCode: Flow<Int?> = context.appPrefsDataStore.data
        .map { it[Keys.LAST_SEEN_VERSION_CODE] }

    suspend fun setLastSeenVersionCode(code: Int) {
        context.appPrefsDataStore.edit { it[Keys.LAST_SEEN_VERSION_CODE] = code }
    }

    val unmountOnAutoLock: Flow<Boolean> = context.appPrefsDataStore.data
        .map { it[Keys.UNMOUNT_ON_AUTO_LOCK] ?: false }

    suspend fun setUnmountOnAutoLock(enabled: Boolean) {
        context.appPrefsDataStore.edit { it[Keys.UNMOUNT_ON_AUTO_LOCK] = enabled }
    }

    /**
     * Whether what is playing may be named outside the app - the system notification, the
     * lock screen, a car head unit, a watch.
     *
     * Off by default, and that default is the one the app is built around: anything the
     * shared MediaSession carries is mirrored past the PIN, past biometrics, past the
     * calculator disguise and past FLAG_SECURE. On is a real choice for someone who wants
     * the track and the cover on their lock screen and does not need the app to hide what
     * it is playing.
     */
    /**
     * Whether a mount that failed may offer to try Argon2id (#177).
     *
     * On by default, because a vault made with Argon2id opens no other way and
     * nothing in the header says which PRF it is. Off for someone who has no such
     * vault and does not want to be asked about one every time a password is
     * mistyped.
     */
    val argon2Offer: Flow<Boolean> = context.appPrefsDataStore.data
        .map { it[Keys.ARGON2_OFFER] ?: true }

    suspend fun setArgon2Offer(enabled: Boolean) {
        context.appPrefsDataStore.edit { it[Keys.ARGON2_OFFER] = enabled }
    }

    val mediaSessionContent: Flow<Boolean> = context.appPrefsDataStore.data
        .map { it[Keys.MEDIA_SESSION_CONTENT] ?: false }

    suspend fun setMediaSessionContent(enabled: Boolean) {
        context.appPrefsDataStore.edit { it[Keys.MEDIA_SESSION_CONTENT] = enabled }
    }

    val receiveShares: Flow<Boolean> = context.appPrefsDataStore.data
        .map { it[Keys.RECEIVE_SHARES] ?: false }

    suspend fun setReceiveShares(enabled: Boolean) {
        context.appPrefsDataStore.edit { it[Keys.RECEIVE_SHARES] = enabled }
    }

    /**
     * When the user first reached the vault list, i.e. finished onboarding and started
     * actually using the app. The support prompt counts from here rather than from the
     * install, so someone who installs and only sets up days later is not asked on their
     * first real day. null = not recorded yet.
     */
    val firstSeenAt: Flow<Long?> = context.appPrefsDataStore.data
        .map { it[Keys.FIRST_SEEN_AT] }

    suspend fun setFirstSeenAt(millis: Long) {
        context.appPrefsDataStore.edit { it[Keys.FIRST_SEEN_AT] = millis }
    }

    /** null = the support prompt has never been shown. */
    val lastSupportPromptAt: Flow<Long?> = context.appPrefsDataStore.data
        .map { it[Keys.LAST_SUPPORT_PROMPT_AT] }

    suspend fun setLastSupportPromptAt(millis: Long) {
        context.appPrefsDataStore.edit { it[Keys.LAST_SUPPORT_PROMPT_AT] = millis }
    }

    val galleryResyncButton: Flow<Boolean> = context.appPrefsDataStore.data
        .map { it[Keys.GALLERY_RESYNC_BUTTON] ?: false }

    suspend fun setGalleryResyncButton(enabled: Boolean) {
        context.appPrefsDataStore.edit { it[Keys.GALLERY_RESYNC_BUTTON] = enabled }
    }

    val defaultContainerTab: Flow<DefaultContainerTab> = context.appPrefsDataStore.data
        .map { prefs ->
            prefs[Keys.DEFAULT_CONTAINER_TAB]
                ?.let { runCatching { DefaultContainerTab.valueOf(it) }.getOrNull() }
                ?: DefaultContainerTab.FILES
        }

    suspend fun setDefaultContainerTab(tab: DefaultContainerTab) {
        context.appPrefsDataStore.edit { it[Keys.DEFAULT_CONTAINER_TAB] = tab.name }
    }
}
