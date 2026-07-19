package zip.arcanum.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricPrompt
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Binds app-entry (whole-app) biometric unlock to a Keystore key that can only be used
 * after a genuine biometric match, closing the gap where a spoofed BiometricPrompt success
 * callback could satisfy a bare, non-CryptoObject prompt (issue #89).
 *
 * A fixed, non-secret sentinel is wrapped under a user-authentication-required key at
 * registration; unlock requires the CryptoObject-bound cipher to unwrap it, which the
 * Keystore refuses without a real biometric. Uses its own key alias, separate from the
 * per-vault key ([BiometricCryptoManager]), so re-registering one never disturbs the other.
 */
@Singleton
class AppEntryBiometricManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val KEY_ALIAS  = "arcanum_app_entry_key"
        private const val PREFS_FILE = "arcanum_app_entry_prefs"
        private const val PREF_ENC   = "app_entry_enc"
        private const val PREF_IV    = "app_entry_iv"
        private const val TRANSFORM  =
            "${KeyProperties.KEY_ALGORITHM_AES}/" +
            "${KeyProperties.BLOCK_MODE_CBC}/" +
            KeyProperties.ENCRYPTION_PADDING_PKCS7
        // Fixed, non-secret sentinel. Its only job is to force a real Keystore cipher
        // operation on unlock - it protects nothing on its own.
        private val TOKEN = "arcanum-app-entry-unlock-token".toByteArray(Charsets.UTF_8)
    }

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, PREFS_FILE, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }

    private fun createKey(): SecretKey {
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                    .setUserAuthenticationRequired(true)
                    .setInvalidatedByBiometricEnrollment(true)
                    .build()
            )
            generateKey()
        }
        return keyStore().getKey(KEY_ALIAS, null) as SecretKey
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = keyStore()
        return if (ks.containsAlias(KEY_ALIAS)) ks.getKey(KEY_ALIAS, null) as SecretKey
               else createKey()
    }

    private fun deleteKey() {
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    /**
     * CryptoObject for registration (ENCRYPT). If a prior key was permanently invalidated
     * by a new biometric enrollment, the stale key/token are dropped and a fresh key is made,
     * so registration self-heals after re-enrollment.
     */
    fun getCryptoObjectForEnroll(): BiometricPrompt.CryptoObject? {
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            BiometricPrompt.CryptoObject(cipher)
        } catch (_: KeyPermanentlyInvalidatedException) {
            deleteKey(); clear()
            runCatching {
                val cipher = Cipher.getInstance(TRANSFORM)
                cipher.init(Cipher.ENCRYPT_MODE, createKey())
                BiometricPrompt.CryptoObject(cipher)
            }.getOrNull()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * CryptoObject for unlock (DECRYPT). Null when nothing is registered, or when the key was
     * invalidated by re-enrollment - in which case the stale token is cleared so the next
     * biometric attempt re-enrolls (see [getCryptoObjectForEnroll]).
     */
    fun getCryptoObjectForUnlock(): BiometricPrompt.CryptoObject? {
        val ivB64 = prefs.getString(PREF_IV, null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                IvParameterSpec(Base64.decode(ivB64, Base64.NO_WRAP))
            )
            BiometricPrompt.CryptoObject(cipher)
        } catch (_: KeyPermanentlyInvalidatedException) {
            deleteKey(); clear()
            null
        } catch (_: Exception) {
            null
        }
    }

    /** Wrap the sentinel with the just-authenticated cipher. Returns true on success. */
    fun saveToken(cipher: Cipher): Boolean = try {
        val enc = cipher.doFinal(TOKEN)
        prefs.edit()
            .putString(PREF_ENC, Base64.encodeToString(enc, Base64.NO_WRAP))
            .putString(PREF_IV,  Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
        true
    } catch (_: Exception) {
        false
    }

    /** Unwrap and verify the sentinel with the just-authenticated cipher. */
    fun verifyToken(cipher: Cipher): Boolean = try {
        val encB64 = prefs.getString(PREF_ENC, null) ?: return false
        cipher.doFinal(Base64.decode(encB64, Base64.NO_WRAP)).contentEquals(TOKEN)
    } catch (_: Exception) {
        false
    }

    fun hasToken(): Boolean = prefs.getString(PREF_ENC, null) != null

    fun clear() {
        prefs.edit().remove(PREF_ENC).remove(PREF_IV).apply()
    }
}
