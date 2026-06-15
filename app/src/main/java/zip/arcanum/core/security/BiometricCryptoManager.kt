package zip.arcanum.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricPrompt
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BiometricCryptoManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val KEY_ALIAS  = "arcanum_biometric_key"
        private const val PREFS_FILE = "arcanum_bio_prefs"
        private const val TRANSFORM  =
            "${KeyProperties.KEY_ALGORITHM_AES}/" +
            "${KeyProperties.BLOCK_MODE_CBC}/" +
            KeyProperties.ENCRYPTION_PADDING_PKCS7
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

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        if (!ks.containsAlias(KEY_ALIAS)) {
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
        }
        return ks.getKey(KEY_ALIAS, null) as SecretKey
    }

    fun getCryptoObjectForEncrypt(): BiometricPrompt.CryptoObject {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return BiometricPrompt.CryptoObject(cipher)
    }

    fun getCryptoObjectForDecrypt(containerId: String): BiometricPrompt.CryptoObject? {
        val ivB64  = prefs.getString("bio_iv_$containerId", null) ?: return null
        val cipher = Cipher.getInstance(TRANSFORM)
        return try {
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                IvParameterSpec(Base64.decode(ivB64, Base64.NO_WRAP))
            )
            BiometricPrompt.CryptoObject(cipher)
        } catch (_: Exception) { null }
    }

    fun saveEncryptedCredentials(containerId: String, cipher: Cipher, password: String, pim: Int) {
        val json      = JSONObject().apply { put("password", password); put("pim", pim) }.toString()
        val encrypted = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("bio_enc_$containerId", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("bio_iv_$containerId",  Base64.encodeToString(cipher.iv,   Base64.NO_WRAP))
            .apply()
    }

    fun loadDecryptedCredentials(containerId: String, cipher: Cipher): Pair<String, Int>? {
        val encB64 = prefs.getString("bio_enc_$containerId", null) ?: return null
        return try {
            val decrypted = cipher.doFinal(Base64.decode(encB64, Base64.NO_WRAP))
            val json      = JSONObject(String(decrypted, Charsets.UTF_8))
            val result    = Pair(json.getString("password"), json.getInt("pim"))
            decrypted.fill(0)
            result
        } catch (_: Exception) { null }
    }

    fun hasSavedCredentials(containerId: String): Boolean =
        prefs.getString("bio_enc_$containerId", null) != null

    fun deleteCredentials(containerId: String) {
        prefs.edit()
            .remove("bio_enc_$containerId")
            .remove("bio_iv_$containerId")
            .apply()
    }
}
