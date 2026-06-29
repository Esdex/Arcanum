package zip.arcanum.core.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabaseKeyManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val prefsDeferred: Deferred<EncryptedSharedPreferences> = ioScope.async {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "arcanum_db_key_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ) as EncryptedSharedPreferences
    }

    suspend fun getPassphrase(): ByteArray = withContext(Dispatchers.IO) {
        val prefs = prefsDeferred.await()
        val stored = prefs.getString(KEY_PASSPHRASE, null)
        if (stored != null) return@withContext Base64.getDecoder().decode(stored)
        val passphrase = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(KEY_PASSPHRASE, Base64.getEncoder().encodeToString(passphrase)).apply()
        passphrase
    }

    // Plaintext DB from older app versions cannot be opened by SupportFactory.
    // Delete it so Room recreates it encrypted — container files on disk are not affected.
    fun migrateIfNeeded() {
        val dbFile = context.getDatabasePath("arcanum.db")
        if (!dbFile.exists() || !isPlaintext(dbFile)) return
        dbFile.delete()
        File("${dbFile.absolutePath}-wal").delete()
        File("${dbFile.absolutePath}-shm").delete()
    }

    private fun isPlaintext(file: File): Boolean {
        val header = ByteArray(16)
        file.inputStream().use { it.read(header) }
        return String(header, Charsets.US_ASCII).startsWith("SQLite format 3")
    }

    companion object {
        private const val KEY_PASSPHRASE = "db_passphrase"
    }
}
