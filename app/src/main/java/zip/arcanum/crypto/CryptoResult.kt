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
    RNG_FAILURE,
    NATIVE_LIBRARY_MISSING,
    /** Native ERR_NO_SPACE: write/format ran out of disk space. */
    NO_SPACE,
    /** Native ERR_READ_ONLY: write blocked because the container is mounted read-only. */
    READ_ONLY,
    /** Native ERR_HIDDEN_BOUNDARY: write blocked by hidden-volume protection. */
    HIDDEN_BOUNDARY_PROTECTED,
    /** Native ERR_NO_SLOT: no free drive slot (MAX_DRIVES containers already mounted). */
    TOO_MANY_MOUNTED,
    UNKNOWN
}
