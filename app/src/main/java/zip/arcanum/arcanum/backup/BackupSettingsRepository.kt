package zip.arcanum.arcanum.backup

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    suspend fun loadSettings(containerId: String): BackupSettings = withContext(Dispatchers.IO) {
        prefs.getString(settingsKey(containerId), null)
            ?.let { runCatching { json.decodeFromString<BackupSettings>(it) }.getOrNull() }
            ?: BackupSettings()
    }

    suspend fun saveSettings(containerId: String, settings: BackupSettings) = withContext(Dispatchers.IO) {
        prefs.edit().putString(settingsKey(containerId), json.encodeToString(settings)).apply()
    }

    suspend fun loadLastRecord(containerId: String, provider: BackupProvider): BackupRecord? = withContext(Dispatchers.IO) {
        prefs.getString(lastRecordKey(containerId, provider), null)
            ?.let { runCatching { json.decodeFromString<BackupRecord>(it) }.getOrNull() }
    }

    suspend fun saveLastRecord(containerId: String, record: BackupRecord) = withContext(Dispatchers.IO) {
        prefs.edit().putString(lastRecordKey(containerId, record.provider), json.encodeToString(record)).apply()
    }

    suspend fun loadS3ResumeState(containerId: String): S3MultipartResumeState? = withContext(Dispatchers.IO) {
        prefs.getString(s3ResumeKey(containerId), null)
            ?.let { runCatching { json.decodeFromString<S3MultipartResumeState>(it) }.getOrNull() }
    }

    suspend fun saveS3ResumeState(state: S3MultipartResumeState) = withContext(Dispatchers.IO) {
        prefs.edit().putString(s3ResumeKey(state.containerId), json.encodeToString(state)).apply()
    }

    suspend fun clearS3ResumeState(containerId: String) = withContext(Dispatchers.IO) {
        prefs.edit().remove(s3ResumeKey(containerId)).apply()
    }

    companion object {
        private const val PREFS_NAME = "backup_secure_prefs"

        private fun settingsKey(containerId: String) = "settings_$containerId"
        private fun lastRecordKey(containerId: String, provider: BackupProvider) = "last_${containerId}_${provider.name}"
        private fun s3ResumeKey(containerId: String) = "s3_resume_$containerId"
    }
}

