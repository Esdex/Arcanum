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
import net.sqlcipher.database.SQLiteDatabase
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

    // Detects a plaintext DB left by older app versions and encrypts it in-place.
    // No-op on fresh installs or already-encrypted databases.
    fun migrateIfNeeded(passphrase: ByteArray) {
        val dbFile = context.getDatabasePath("arcanum.db")
        if (!dbFile.exists() || !isPlaintext(dbFile)) return
        SQLiteDatabase.loadLibs(context)
        try {
            SQLiteDatabase.openDatabase(
                dbFile.absolutePath, ByteArray(0), null, SQLiteDatabase.OPEN_READWRITE
            ).use { db -> db.changePassword(passphrase) }
        } catch (_: Exception) {
            // If migration fails, delete the metadata DB — containers (files on disk) are safe.
            dbFile.delete()
        }
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
