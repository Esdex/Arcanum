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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import zip.arcanum.core.database.dao.CalculatorHistoryDao
import zip.arcanum.core.database.dao.ContainerDao
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private val Context.panicDataStore: DataStore<Preferences> by preferencesDataStore(name = "panic_settings")

@Serializable
enum class VaultPanicAction { DELETE, FORGET, KEEP }

@Serializable
data class PanicSettings(
    val enabled: Boolean = false,
    val fullWipe: Boolean = false,
    val clearSettings: Boolean = true,
    val clearCalculatorHistory: Boolean = true,
    val disableBiometric: Boolean = true,
    val vaultActions: Map<String, VaultPanicAction> = emptyMap()
)

@Singleton
class PanicManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pinManager: PinManager,
    private val containerDao: ContainerDao,
    private val historyDao: CalculatorHistoryDao,
    private val biometricCryptoManager: BiometricCryptoManager
) {
    private object Keys {
        val ENABLED            = booleanPreferencesKey("enabled")
        val FULL_WIPE          = booleanPreferencesKey("full_wipe")
        val CLEAR_SETTINGS     = booleanPreferencesKey("clear_settings")
        val CLEAR_CALC_HISTORY = booleanPreferencesKey("clear_calc_history")
        val DISABLE_BIOMETRIC  = booleanPreferencesKey("disable_biometric")
        val VAULT_ACTIONS      = stringPreferencesKey("vault_actions")
    }

    val panicSettingsFlow: Flow<PanicSettings> = context.panicDataStore.data.map { prefs ->
        val actionsJson = prefs[Keys.VAULT_ACTIONS]
        val actions: Map<String, VaultPanicAction> = if (actionsJson != null) {
            try {
                Json.decodeFromString<Map<String, String>>(actionsJson)
                    .mapValues { (_, v) -> VaultPanicAction.valueOf(v) }
            } catch (_: Exception) { emptyMap() }
        } else emptyMap()
        PanicSettings(
            enabled                = prefs[Keys.ENABLED] ?: false,
            fullWipe               = prefs[Keys.FULL_WIPE] ?: false,
            clearSettings          = prefs[Keys.CLEAR_SETTINGS] ?: true,
            clearCalculatorHistory = prefs[Keys.CLEAR_CALC_HISTORY] ?: true,
            disableBiometric       = prefs[Keys.DISABLE_BIOMETRIC] ?: true,
            vaultActions           = actions
        )
    }

    suspend fun savePanicSettings(settings: PanicSettings) {
        val actionsJson = Json.encodeToString(
            settings.vaultActions.mapValues { (_, v) -> v.name }
        )
        context.panicDataStore.edit { prefs ->
            prefs[Keys.ENABLED]            = settings.enabled
            prefs[Keys.FULL_WIPE]          = settings.fullWipe
            prefs[Keys.CLEAR_SETTINGS]     = settings.clearSettings
            prefs[Keys.CLEAR_CALC_HISTORY] = settings.clearCalculatorHistory
            prefs[Keys.DISABLE_BIOMETRIC]  = settings.disableBiometric
            prefs[Keys.VAULT_ACTIONS]      = actionsJson
        }
    }

    suspend fun getPanicSettings(): PanicSettings = panicSettingsFlow.first()

    suspend fun resetPanicMode() {
        context.panicDataStore.edit { it.clear() }
        pinManager.clearPanicPin()
    }

    // Step 1: call synchronously before navigation.
    // Reads settings, promotes the panic PIN to main (invalidates the real PIN immediately),
    // and returns the settings needed for the wipe — or null if panic mode is disabled.
    suspend fun prepareForPanic(): PanicSettings? {
        val settings = getPanicSettings()
        if (!settings.enabled) return null
        pinManager.promotePanicPinToMain()
        return settings
    }

    // Step 2: call in a durable background job after navigation.
    // Re-reads settings from DataStore so nothing sensitive is passed through
    // WorkManager's plaintext SQLite database. DataStore is cleared on completion,
    // making retries safe: a completed wipe returns early via the enabled check.
    suspend fun executeWipe() {
        val settings = getPanicSettings()
        if (!settings.enabled) return
        if (settings.fullWipe) {
            val all = containerDao.getAllContainersOnce()
            all.forEach { entity ->
                secureDeleteFile(entity.path)
                biometricCryptoManager.deleteCredentials(entity.id)
            }
            containerDao.deleteAll()
            historyDao.clearHistory()
        } else {
            if (settings.clearCalculatorHistory) historyDao.clearHistory()
            if (settings.disableBiometric) {
                containerDao.getAllContainersOnce().forEach { entity ->
                    biometricCryptoManager.deleteCredentials(entity.id)
                }
                containerDao.clearAllBiometric()
            }
            settings.vaultActions.forEach { (containerId, action) ->
                when (action) {
                    VaultPanicAction.DELETE -> {
                        containerDao.getContainerById(containerId)?.let { entity ->
                            secureDeleteFile(entity.path)
                            containerDao.deleteContainer(entity)
                        }
                    }
                    VaultPanicAction.FORGET -> containerDao.deleteContainerById(containerId)
                    VaultPanicAction.KEEP   -> {}
                }
            }
        }
        context.panicDataStore.edit { it.clear() }
    }

    suspend fun executePanic() {
        prepareForPanic() ?: return
        executeWipe()
    }

    private fun secureDeleteFile(path: String) {
        // VeraCrypt containers are AES-256 encrypted — raw bytes are worthless without
        // the container password. Multi-pass overwrite is also ineffective on eMMC/UFS
        // (wear leveling redirects writes to different physical cells). Simple unlink is
        // sufficient and keeps panic timing indistinguishable from a normal unlock.
        File(path).delete()
    }
}
