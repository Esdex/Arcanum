package zip.arcanum.crypto

sealed class CryptoResult<out T> {
    data class Success<T>(val value: T) : CryptoResult<T>()
    data class Failure(val error: CryptoError) : CryptoResult<Nothing>()
}

enum class CryptoError {
    WRONG_PASSWORD,
    CORRUPTED_CONTAINER,
    UNSUPPORTED_ALGORITHM,
    UNSUPPORTED_OPERATION,
    IO_ERROR,
    FILESYSTEM_ERROR,
    NO_SPACE,
    HIDDEN_BOUNDARY,
    HIDDEN_CREDENTIALS_REQUIRED,
    RNG_FAILURE,
    NATIVE_LIBRARY_MISSING,
    UNKNOWN
}
