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
    /**
     * Native ERR_DIR_FULL: the directory cannot hold another entry. On FAT12/16
     * the root directory is a fixed 512 entries and a long filename consumes
     * several, so a root can fill up while the volume is nearly empty. Distinct
     * from [NO_SPACE] because the remedy is different: use a subfolder.
     */
    DIRECTORY_FULL,
    /**
     * Native ERR_TOO_FRAGMENTED: an ext4 file is in so many pieces that its extent
     * tree cannot describe another one, and the write is refused. Distinct from
     * [NO_SPACE] because the vault is not full - it has blocks free, just nowhere
     * left to record where this file's next ones went. Copying the vault's contents
     * into a fresh vault lays the files out in one piece again and clears it.
     */
    TOO_FRAGMENTED,
    /** Native ERR_HIDDEN_BOUNDARY: write blocked by hidden-volume protection. */
    HIDDEN_BOUNDARY_PROTECTED,
    /**
     * Native ERR_BUSY: refused because the volume is mounted. Raised by header
     * restore only - a restored header may name a different master key, and a
     * mounted drive would keep writing with the keys it already holds.
     */
    BUSY,
    /** Native ERR_NO_SLOT: no free drive slot (MAX_DRIVES containers already mounted). */
    TOO_MANY_MOUNTED,
    UNKNOWN
}
