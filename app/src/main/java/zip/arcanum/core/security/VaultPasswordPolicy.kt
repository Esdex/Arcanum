package zip.arcanum.core.security

object VaultPasswordPolicy {
    const val MAX_PASSWORD_BYTES = 128

    fun isWithinVeraCryptLimit(password: String): Boolean =
        password.toByteArray(Charsets.UTF_8).size <= MAX_PASSWORD_BYTES

    fun hasUnsafeLowPim(password: String, pim: Int): Boolean =
        pim in 1..484 && password.length < 20

    fun violationMessage(): String =
        "VeraCrypt passwords are limited to $MAX_PASSWORD_BYTES UTF-8 bytes"

    fun lowPimViolationMessage(): String =
        "Passwords shorter than 20 characters require PIM 485 or higher"
}
