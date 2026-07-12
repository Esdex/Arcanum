package zip.arcanum.core.security

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BiometricAuth @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Authenticator set for "prove you're the device owner" prompts that must offer a
     * device-credential (PIN/pattern/password) fallback.
     *
     * AndroidX BiometricPrompt only accepts BIOMETRIC_STRONG|DEVICE_CREDENTIAL on API < 28
     * or API >= 30 - on API 28/29 PromptInfo.Builder.build() throws IllegalArgumentException
     * ("Authenticator combination is unsupported"), which crashes the caller synchronously.
     * On those two API levels the only supported credential-fallback combination is
     * BIOMETRIC_WEAK|DEVICE_CREDENTIAL, so use it there. These prompts are ownership gates,
     * not CryptoObject-bound vault unlocks, so allowing a Class-2 biometric is acceptable.
     */
    private fun ownershipAuthenticators(): Int =
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.P..Build.VERSION_CODES.Q) {
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        }

    fun isAvailable(): Boolean {
        val manager = BiometricManager.from(context)
        return manager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun hasDeviceLock(): Boolean {
        val manager = BiometricManager.from(context)
        return manager.canAuthenticate(
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun authenticateForDebug(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onError: (Int, CharSequence) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onError(errorCode, errString)
            override fun onAuthenticationFailed() {}
        }
        // Build + authenticate can throw synchronously (unsupported authenticator combination,
        // no enrolled credential on some OEM builds). Route any failure to onError instead of
        // letting it crash the caller.
        runCatching {
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Debug Mode")
                .setSubtitle("Authenticate to access developer tools")
                .setAllowedAuthenticators(ownershipAuthenticators())
                .build()
            BiometricPrompt(activity, executor, callback).authenticate(promptInfo)
        }.onFailure { e ->
            onError(BiometricPrompt.ERROR_HW_UNAVAILABLE, e.message ?: "Authentication unavailable")
        }
    }

    /**
     * App-entry biometric unlock. Gated on the success callback of a bare
     * BiometricPrompt, NOT bound to a CryptoObject / keystore operation.
     *
     * Limitation (documented, accepted): on a rooted or instrumented device the
     * success callback can be driven directly without a real biometric match,
     * bypassing this gate. Non-root devices are unaffected. This is bounded on
     * purpose — passing this gate only reveals the vault *list*; each vault is
     * separately encrypted and mounting it requires the vault password or the
     * per-vault biometric, which IS CryptoObject-bound (Keystore key with
     * setUserAuthenticationRequired). So no plaintext vault data is exposed by a
     * spoofed app-entry unlock. Binding app entry to a keystore-unwrapped token
     * would close the residual gap; deferred (see issue #88 item 6).
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String = "Biometric Authentication",
        subtitle: String = "Confirm your identity to access Arcanum",
        onSuccess: () -> Unit,
        onError: (Int, CharSequence) -> Unit,
        onFailed: () -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onError(errorCode, errString)
            override fun onAuthenticationFailed() = onFailed()
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText("Use PIN")
            .build()

        BiometricPrompt(activity, executor, callback).authenticate(promptInfo)
    }

    fun authenticateWithDeviceLock(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onError: (Int, CharSequence) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onError(errorCode, errString)
            override fun onAuthenticationFailed() {}
        }
        runCatching {
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(ownershipAuthenticators())
                .build()
            BiometricPrompt(activity, executor, callback).authenticate(promptInfo)
        }.onFailure { e ->
            onError(BiometricPrompt.ERROR_HW_UNAVAILABLE, e.message ?: "Authentication unavailable")
        }
    }
}
