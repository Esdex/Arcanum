package zip.arcanum.core.security

object AppPasswordPolicy {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 128

    fun sanitize(value: String): String = value.take(MAX_LENGTH)

    fun isValid(value: String): Boolean = value.length in MIN_LENGTH..MAX_LENGTH
}
