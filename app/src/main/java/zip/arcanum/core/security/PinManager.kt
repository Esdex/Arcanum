package zip.arcanum.core.security

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

enum class PinResult { NORMAL, PANIC, WRONG, LOCKED }

@Singleton
class PinManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val failCountMutex = Mutex()

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
        val encoded = hashArgon2(pin)
        val ok = prefsDeferred.await().edit()
            .putString(KEY_PIN_HASH, encoded)
            .putInt(KEY_PIN_HASH_VERSION, VERSION_ARGON2)
            .commit()
        check(ok) { "Failed to persist PIN" }
        _isPinSet.value = true
    }

    suspend fun savePanicPin(pin: String) = withContext(Dispatchers.IO) {
        val encoded = hashArgon2(pin)
        val ok = prefsDeferred.await().edit()
            .putString(KEY_PANIC_PIN_HASH, encoded)
            .putInt(KEY_PANIC_HASH_VERSION, VERSION_ARGON2)
            .commit()
        check(ok) { "Failed to persist panic PIN" }
        _isPanicPinSet.value = true
    }

    suspend fun clearPanicPin() = withContext(Dispatchers.IO) {
        prefsDeferred.await().edit()
            .remove(KEY_PANIC_PIN_HASH)
            .remove(KEY_PANIC_HASH_VERSION)
            .apply()
        _isPanicPinSet.value = false
    }

    /** Returns remaining lockout time in ms, or 0 if not locked. */
    suspend fun lockoutRemainingMs(): Long = withContext(Dispatchers.IO) {
        remainingLockMs(prefsDeferred.await())
    }

    suspend fun verifyPin(pin: String): PinResult = withContext(Dispatchers.IO) {
        val prefs = prefsDeferred.await()

        if (remainingLockMs(prefs) > 0L) return@withContext PinResult.LOCKED

        val pinHash   = prefs.getString(KEY_PIN_HASH, null)
        val panicHash = prefs.getString(KEY_PANIC_PIN_HASH, null)
        val pinVer    = prefs.getInt(KEY_PIN_HASH_VERSION, VERSION_SHA256)
        val panicVer  = prefs.getInt(KEY_PANIC_HASH_VERSION, VERSION_SHA256)

        // Always run both derivations regardless of whether the hash is set.
        // Substituting DUMMY_HASH when absent ensures the Argon2id work executes either way,
        // preventing an attacker from inferring whether a panic PIN is configured via timing.
        val mainOk  = verifyHash(pin, pinHash   ?: DUMMY_HASH, if (pinHash   != null) pinVer   else VERSION_ARGON2)
        val panicOk = verifyHash(pin, panicHash ?: DUMMY_HASH, if (panicHash != null) panicVer else VERSION_ARGON2)
        val matchesMain  = pinHash  != null && mainOk
        val matchesPanic = panicHash != null && panicOk

        when {
            matchesMain -> {
                if (pinVer == VERSION_SHA256) migrateToArgon2(pin, prefs, isMain = true)
                resetFailCount(prefs)
                PinResult.NORMAL
            }
            matchesPanic -> {
                if (panicVer == VERSION_SHA256) migrateToArgon2(pin, prefs, isMain = false)
                resetFailCount(prefs)
                PinResult.PANIC
            }
            else -> {
                failCountMutex.withLock {
                    val newCount = prefs.getInt(KEY_FAIL_COUNT, 0) + 1
                    val lockDuration = lockoutDuration(newCount)
                    val editor = prefs.edit().putInt(KEY_FAIL_COUNT, newCount)
                    if (lockDuration > 0L) {
                        val nowElapsed = SystemClock.elapsedRealtime()
                        editor.putLong(KEY_LOCK_SET_ELAPSED, nowElapsed)
                            .putLong(KEY_LOCK_UNTIL_ELAPSED, nowElapsed + lockDuration)
                            .putLong(KEY_LOCK_UNTIL_WALL, System.currentTimeMillis() + lockDuration)
                    } else {
                        editor.putLong(KEY_LOCK_SET_ELAPSED, 0L)
                            .putLong(KEY_LOCK_UNTIL_ELAPSED, 0L)
                            .putLong(KEY_LOCK_UNTIL_WALL, 0L)
                    }
                    editor.commit()
                }
                PinResult.WRONG
            }
        }
    }

    suspend fun isPanicPinSet(): Boolean = withContext(Dispatchers.IO) {
        prefsDeferred.await().getString(KEY_PANIC_PIN_HASH, null) != null
    }

    suspend fun promotePanicPinToMain() = withContext(Dispatchers.IO) {
        val prefs     = prefsDeferred.await()
        val panicHash = prefs.getString(KEY_PANIC_PIN_HASH, null) ?: return@withContext
        val panicVer  = prefs.getInt(KEY_PANIC_HASH_VERSION, VERSION_SHA256)
        // commit() instead of apply() — synchronous fsync guarantees the invalidation
        // survives a power-pull between promotion and the background wipe completing.
        prefs.edit()
            .putString(KEY_PIN_HASH, panicHash)
            .putInt(KEY_PIN_HASH_VERSION, panicVer)
            .remove(KEY_PANIC_PIN_HASH)
            .remove(KEY_PANIC_HASH_VERSION)
            .commit()
        _isPinSet.value      = true
        _isPanicPinSet.value = false
    }

    // Mirrors the IO cost of promotePanicPinToMain() on the normal-unlock path so both
    // paths have the same pre-navigation latency (timing equalization for deniability).
    suspend fun dummyPromote() = withContext(Dispatchers.IO) {
        val prefs   = prefsDeferred.await()
        val pinHash = prefs.getString(KEY_PIN_HASH, null) ?: return@withContext
        val pinVer  = prefs.getInt(KEY_PIN_HASH_VERSION, VERSION_SHA256)
        prefs.edit()
            .putString(KEY_PIN_HASH, pinHash)
            .putInt(KEY_PIN_HASH_VERSION, pinVer)
            .commit()
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        prefsDeferred.await().edit()
            .remove(KEY_PIN_HASH)
            .remove(KEY_PIN_HASH_VERSION)
            .remove(KEY_PANIC_PIN_HASH)
            .remove(KEY_PANIC_HASH_VERSION)
            .remove(KEY_FAIL_COUNT)
            .remove(KEY_LOCK_SET_ELAPSED)
            .remove(KEY_LOCK_UNTIL_ELAPSED)
            .remove(KEY_LOCK_UNTIL_WALL)
            .apply()
        _isPinSet.value      = false
        _isPanicPinSet.value = false
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private fun hashArgon2(pin: String): String {
        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        val hash = deriveArgon2(pin.toByteArray(Charsets.UTF_8), salt)
        return Base64.getEncoder().encodeToString(salt) + ":" +
               Base64.getEncoder().encodeToString(hash)
    }

    private fun verifyHash(pin: String, stored: String, version: Int): Boolean {
        return if (version == VERSION_ARGON2) {
            val parts = stored.split(":")
            if (parts.size != 2) return false
            val salt       = Base64.getDecoder().decode(parts[0])
            val storedHash = Base64.getDecoder().decode(parts[1])
            val inputHash  = deriveArgon2(pin.toByteArray(Charsets.UTF_8), salt)
            MessageDigest.isEqual(inputHash, storedHash)
        } else {
            // Legacy SHA-256 path — used only during one-time migration
            val inputHex = sha256hex(pin)
            MessageDigest.isEqual(inputHex.toByteArray(), stored.toByteArray())
        }
    }

    private fun deriveArgon2(password: ByteArray, salt: ByteArray): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(salt)
            .withMemoryAsKB(ARGON2_MEMORY_KB)
            .withIterations(ARGON2_ITERS)
            .withParallelism(ARGON2_PARALLEL)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .build()
        val gen    = Argon2BytesGenerator()
        gen.init(params)
        val output = ByteArray(HASH_LEN)
        gen.generateBytes(password, output, 0, HASH_LEN)
        return output
    }

    private fun sha256hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.fold("") { acc, b -> acc + "%02x".format(b) }
    }

    private fun migrateToArgon2(pin: String, prefs: SharedPreferences, isMain: Boolean) {
        val encoded = hashArgon2(pin)
        if (isMain) {
            prefs.edit()
                .putString(KEY_PIN_HASH, encoded)
                .putInt(KEY_PIN_HASH_VERSION, VERSION_ARGON2)
                .apply()
        } else {
            prefs.edit()
                .putString(KEY_PANIC_PIN_HASH, encoded)
                .putInt(KEY_PANIC_HASH_VERSION, VERSION_ARGON2)
                .apply()
        }
    }

    private fun resetFailCount(prefs: SharedPreferences) {
        prefs.edit()
            .putInt(KEY_FAIL_COUNT, 0)
            .putLong(KEY_LOCK_SET_ELAPSED, 0L)
            .putLong(KEY_LOCK_UNTIL_ELAPSED, 0L)
            .putLong(KEY_LOCK_UNTIL_WALL, 0L)
            .apply()
    }

    /**
     * Remaining lockout in ms, evaluated against both a monotonic clock
     * (SystemClock.elapsedRealtime) and the wall clock, fail-closed. 0 = unlocked.
     *
     * elapsedRealtime can't be moved forward from Settings, so it defeats the
     * "advance the system clock past the deadline" bypass. It does reset to 0 on
     * reboot, so the wall-clock deadline is kept as a reboot fallback: the vault
     * stays locked while EITHER clock still says locked. Moving the wall clock
     * forward zeroes only the wall term (monotonic still governs); moving it
     * backward can only over-lock. The residual gap (reboot AND clock-forward)
     * is bounded by Argon2id making each guess expensive.
     */
    private fun remainingLockMs(prefs: SharedPreferences): Long {
        val untilElapsed = prefs.getLong(KEY_LOCK_UNTIL_ELAPSED, 0L)
        val untilWall    = prefs.getLong(KEY_LOCK_UNTIL_WALL, 0L)
        if (untilElapsed == 0L && untilWall == 0L) return 0L

        val nowElapsed = SystemClock.elapsedRealtime()
        val setElapsed = prefs.getLong(KEY_LOCK_SET_ELAPSED, 0L)
        // If "now" precedes when the lock was set, elapsedRealtime has reset (reboot),
        // so the monotonic deadline is meaningless — drop it and rely on the wall clock.
        val remElapsed = if (nowElapsed < setElapsed) 0L else untilElapsed - nowElapsed
        val remWall    = untilWall - System.currentTimeMillis()

        return maxOf(0L, remElapsed, remWall)
    }

    private fun lockoutDuration(failCount: Int): Long = when {
        failCount < 5  -> 0L
        failCount < 8  -> 30_000L       // 30 seconds
        failCount < 12 -> 300_000L      // 5 minutes
        failCount < 15 -> 1_800_000L    // 30 minutes
        else           -> 7_200_000L    // 2 hours
    }

    companion object {
        private const val KEY_PIN_HASH           = "pin_hash"
        private const val KEY_PIN_HASH_VERSION   = "pin_hash_v"
        private const val KEY_PANIC_PIN_HASH     = "panic_pin_hash"
        private const val KEY_PANIC_HASH_VERSION = "panic_pin_hash_v"
        private const val KEY_FAIL_COUNT         = "pin_fail_count"
        // Lockout deadlines: monotonic (elapsedRealtime) primary + wall-clock reboot fallback.
        private const val KEY_LOCK_SET_ELAPSED   = "pin_lock_set_elapsed"
        private const val KEY_LOCK_UNTIL_ELAPSED = "pin_lock_until_elapsed"
        private const val KEY_LOCK_UNTIL_WALL    = "pin_lock_until_wall"

        private const val VERSION_SHA256 = 1
        private const val VERSION_ARGON2 = 2

        private const val ARGON2_ITERS     = 2
        private const val ARGON2_MEMORY_KB = 65536  // 64 MB
        private const val ARGON2_PARALLEL  = 1
        private const val SALT_LEN         = 32
        private const val HASH_LEN         = 32

        // Stable dummy Argon2id-format hash (base64-salt:base64-hash).
        // Used when a hash slot is absent so the full derivation still executes.
        // 44-char base64 = 32 zero bytes; will never match a real PIN.
        private const val DUMMY_HASH =
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=:" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    }
}
