package zip.arcanum.crypto

sealed class CryptoResult<out T> {
    data class Success<T>(val value: T) : CryptoResult<T>()
    data class Failure(val error: CryptoError) : CryptoResult<Nothing>()
}

enum class CryptoError {
    WRONG_PASSWORD,
    CORRUPTED_CONTAINER,
    UNSUPPORTED_ALGORITHM,
    IO_ERROR,
    NATIVE_LIBRARY_MISSING,
    UNKNOWN
}
