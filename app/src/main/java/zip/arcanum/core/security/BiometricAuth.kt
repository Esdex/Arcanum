package zip.arcanum.core.security

import android.content.Context
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
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Debug Mode")
            .setSubtitle("Authenticate to access developer tools")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        BiometricPrompt(activity, executor, callback).authenticate(promptInfo)
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
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        BiometricPrompt(activity, executor, callback).authenticate(promptInfo)
    }
}
