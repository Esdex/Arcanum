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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

enum class PinResult { NORMAL, PANIC, WRONG }

@Singleton
class PinManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Starts immediately at injection time — not lazily on first use
    private val prefsDeferred: Deferred<EncryptedSharedPreferences> = ioScope.async {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "arcanum_pin_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ) as EncryptedSharedPreferences
    }

    private val _isPinSet      = MutableStateFlow<Boolean?>(null)
    val isPinSetFlow: StateFlow<Boolean?> = _isPinSet.asStateFlow()

    private val _isPanicPinSet = MutableStateFlow(false)
    val isPanicPinSetFlow: StateFlow<Boolean> = _isPanicPinSet.asStateFlow()

    init {
        ioScope.launch {
            val prefs = prefsDeferred.await()
            _isPinSet.value      = prefs.getString(KEY_PIN_HASH, null) != null
            _isPanicPinSet.value = prefs.getString(KEY_PANIC_PIN_HASH, null) != null
        }
    }

    suspend fun savePin(pin: String) = withContext(Dispatchers.IO) {
        prefsDeferred.await().edit().putString(KEY_PIN_HASH, hash(pin)).apply()
        _isPinSet.value = true
    }

    suspend fun savePanicPin(pin: String) = withContext(Dispatchers.IO) {
        prefsDeferred.await().edit().putString(KEY_PANIC_PIN_HASH, hash(pin)).apply()
        _isPanicPinSet.value = true
    }

    suspend fun clearPanicPin() = withContext(Dispatchers.IO) {
        prefsDeferred.await().edit().remove(KEY_PANIC_PIN_HASH).apply()
        _isPanicPinSet.value = false
    }

    suspend fun verifyPin(pin: String): PinResult = withContext(Dispatchers.IO) {
        val inputHash = hash(pin)
        val prefs = prefsDeferred.await()
        when {
            inputHash == prefs.getString(KEY_PIN_HASH, null)       -> PinResult.NORMAL
            inputHash == prefs.getString(KEY_PANIC_PIN_HASH, null) -> PinResult.PANIC
            else                                                    -> PinResult.WRONG
        }
    }

    suspend fun isPanicPinSet(): Boolean = withContext(Dispatchers.IO) {
        prefsDeferred.await().getString(KEY_PANIC_PIN_HASH, null) != null
    }

    suspend fun promotePanicPinToMain() = withContext(Dispatchers.IO) {
        val prefs      = prefsDeferred.await()
        val panicHash  = prefs.getString(KEY_PANIC_PIN_HASH, null) ?: return@withContext
        prefs.edit()
            .putString(KEY_PIN_HASH, panicHash)
            .remove(KEY_PANIC_PIN_HASH)
            .apply()
        _isPinSet.value      = true
        _isPanicPinSet.value = false
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        prefsDeferred.await().edit()
            .remove(KEY_PIN_HASH)
            .remove(KEY_PANIC_PIN_HASH)
            .apply()
        _isPinSet.value      = false
        _isPanicPinSet.value = false
    }

    private fun hash(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.fold("") { acc, byte -> acc + "%02x".format(byte) }
    }

    companion object {
        private const val KEY_PIN_HASH       = "pin_hash"
        private const val KEY_PANIC_PIN_HASH = "panic_pin_hash"
    }
}
