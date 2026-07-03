package zip.arcanum.core.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricPrompt
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppBiometricUnlockManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun hasEnrollment(): Boolean =
        prefs.getString(KEY_SECRET_ENC, null) != null &&
            prefs.getString(KEY_SECRET_IV, null) != null &&
            prefs.getString(KEY_SECRET_DIGEST, null) != null

    fun getCryptoObjectForEnroll(): BiometricPrompt.CryptoObject? {
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            BiometricPrompt.CryptoObject(cipher)
        } catch (_: KeyPermanentlyInvalidatedException) {
            clearEnrollment()
            getCryptoObjectForEnroll()
        } catch (_: Exception) {
            null
        }
    }

    fun completeEnrollment(cipher: Cipher): Boolean {
        val secret = ByteArray(APP_SECRET_BYTES).also { SecureRandom().nextBytes(it) }
        return try {
            val encrypted = cipher.doFinal(secret)
            val digest = digest(secret)
            prefs.edit()
                .putString(KEY_SECRET_ENC, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(KEY_SECRET_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString(KEY_SECRET_DIGEST, Base64.encodeToString(digest, Base64.NO_WRAP))
                .apply()
            true
        } catch (_: Exception) {
            false
        } finally {
            secret.fill(0)
        }
    }

    fun getCryptoObjectForUnlock(): BiometricPrompt.CryptoObject? {
        val ivB64 = prefs.getString(KEY_SECRET_IV, null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                IvParameterSpec(Base64.decode(ivB64, Base64.NO_WRAP))
            )
            BiometricPrompt.CryptoObject(cipher)
        } catch (_: KeyPermanentlyInvalidatedException) {
            clearEnrollment()
            null
        } catch (_: Exception) {
            null
        }
    }

    fun verifyUnlock(cipher: Cipher): Boolean {
        val encB64 = prefs.getString(KEY_SECRET_ENC, null) ?: return false
        val digestB64 = prefs.getString(KEY_SECRET_DIGEST, null) ?: return false
        val secret = try {
            cipher.doFinal(Base64.decode(encB64, Base64.NO_WRAP))
        } catch (_: Exception) {
            clearEnrollment()
            return false
        }
        return try {
            val expected = Base64.decode(digestB64, Base64.NO_WRAP)
            MessageDigest.isEqual(digest(secret), expected)
        } finally {
            secret.fill(0)
        }
    }

    fun clearEnrollment() {
        prefs.edit()
            .remove(KEY_SECRET_ENC)
            .remove(KEY_SECRET_IV)
            .remove(KEY_SECRET_DIGEST)
            .apply()
        deleteKey()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(keySpec())
                generateKey()
            }
        }
        return keyStore.getKey(KEY_ALIAS, null) as SecretKey
    }

    private fun keySpec(): KeyGenParameterSpec {
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        return builder.build()
    }

    private fun deleteKey() {
        runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        }
    }

    private fun digest(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    companion object {
        private const val KEY_ALIAS = "arcanum_app_biometric_unlock_key"
        private const val PREFS_FILE = "arcanum_app_biometric_unlock"
        private const val KEY_SECRET_ENC = "secret_enc"
        private const val KEY_SECRET_IV = "secret_iv"
        private const val KEY_SECRET_DIGEST = "secret_digest"
        private const val APP_SECRET_BYTES = 32
        private const val TRANSFORM =
            "${KeyProperties.KEY_ALGORITHM_AES}/" +
                "${KeyProperties.BLOCK_MODE_CBC}/" +
                KeyProperties.ENCRYPTION_PADDING_PKCS7
    }
}
