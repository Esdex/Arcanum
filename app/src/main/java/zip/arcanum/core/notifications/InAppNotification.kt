package zip.arcanum.core.notifications

/**
 * Why an import stopped, mapped from the native write error.
 *
 * These exist as a small closed set rather than a raw error code so the banner
 * can give advice that actually applies: a full directory and a full volume
 * need opposite actions from the user.
 */
enum class ImportFailureReason {
    /** Directory cannot hold another entry - remedy is a subfolder. */
    DIRECTORY_FULL,
    /** Volume is out of clusters - remedy is freeing space, or moving to a larger vault. */
    NO_SPACE,
    /**
     * ext4 only: the file is in too many pieces for its extent tree to record
     * another. Separate from [NO_SPACE] for the same reason [DIRECTORY_FULL] is -
     * the vault has free space, so telling the user it is full sends them to free
     * up room that will not help (#125). Remedy is a fresh vault to copy into.
     */
    TOO_FRAGMENTED,
    /** Vault turned out to be read-only underneath the UI's read-write state. */
    READ_ONLY,
    /** Anything else: I/O failure, unreadable source, unusable filename. */
    UNKNOWN
}

sealed class InAppNotification {
    abstract val priority: Int
    open val bannerKey: String get() = this::class.simpleName ?: ""
    open val persistent: Boolean get() = false

    data class VaultMounted(
        val vaultId: String,
        val vaultName: String
    ) : InAppNotification() {
        override val priority = 1
    }

    data class VaultUnmounted(
        val vaultId: String,
        val vaultName: String
    ) : InAppNotification() {
        override val priority = 1
    }

    /**
     * An ext4 vault was opened whose superblock says the last session that wrote to
     * it did not finish - the app killed, the battery gone, a drive pulled (#142).
     *
     * Deliberately not an error. The vault is mounted and every file in it is
     * readable and writable; what a cut-short write leaves behind is bookkeeping a
     * check tidies, and it is left to the user whether to bother. Raised once, when
     * the vault is opened, because the first write clears the flag.
     */
    data object VaultNeedsCheck : InAppNotification() {
        override val priority = 2
    }

    /**
     * A USB-hosted vault finished unmounting and the drive can be unplugged.
     *
     * Distinct from [VaultUnmounted] because the user has a physical action to take, and
     * for this vault kind forgetting it risks data rather than tidiness: the drive's own
     * write cache cannot be flushed on demand.
     */
    data class UsbSafeToRemove(
        val vaultId: String,
        val vaultName: String
    ) : InAppNotification() {
        override val priority = 1
    }

    /**
     * A paste where items did not make it. Until this existed a failed paste looked
     * exactly like a successful one - silence either way (#129).
     */
    data class FilesPasteFailed(val failed: Int, val total: Int) : InAppNotification() {
        override val priority = 3
    }

    /** A move into the folder the items are already in: correct, and worth saying. */
    data object FilesAlreadyHere : InAppNotification() {
        override val priority = 1
    }

    /**
     * Copy and Cut with a single vault mounted leave no trace on screen - the only sign
     * is a Paste entry appearing inside the overflow menu, which has to be opened to be
     * found. The reporter of #129 read that as a dead button. These say what happened
     * and where the other half of the operation lives.
     */
    data class FilesCopied(val count: Int) : InAppNotification() {
        override val priority = 1
    }

    data class FilesCut(val count: Int) : InAppNotification() {
        override val priority = 1
    }

    /** Tapping the hero icon for details on a vault that is not open. */
    data object DetailsNeedMount : InAppNotification() {
        override val priority = 2
    }

    /** Tapping the lock with nothing to unlock with - a prompt, not a failure. */
    data object MountNeedsCredentials : InAppNotification() {
        override val priority = 2
    }

    data class VaultError(
        val vaultId: String,
        val message: String
    ) : InAppNotification() {
        override val priority = 2
    }

    data class ExportSuccess(
        val fileName: String
    ) : InAppNotification() {
        override val priority = 1
    }

    data class VaultAdded(
        val fileName: String
    ) : InAppNotification() {
        override val priority = 1
    }

    data class VaultAlreadyExists(
        val fileName: String
    ) : InAppNotification() {
        override val priority = 2
    }

    data object VaultInvalidFile : InAppNotification() {
        override val priority = 2
    }

    data class VaultAddError(
        val message: String
    ) : InAppNotification() {
        override val priority = 2
    }

    data object PanicExecuted : InAppNotification() {
        override val priority = 0
    }

    data object SupportDeveloper : InAppNotification() {
        override val priority = 4
    }

    data object DateUpdated : InAppNotification() {
        override val priority = 1
    }

    data class FileRenamed(val newName: String) : InAppNotification() {
        override val priority = 1
    }

    data class FilesPasted(val count: Int) : InAppNotification() {
        override val priority = 1
    }

    data class FilesMoved(val count: Int, val destinationName: String) : InAppNotification() {
        override val priority = 1
    }

    data class FilesDeleted(val count: Int) : InAppNotification() {
        override val priority = 1
    }

    data class FolderCreated(val name: String) : InAppNotification() {
        override val priority = 1
    }

    data class FilesImported(val count: Int) : InAppNotification() {
        override val priority = 1
    }

    data class FilesExported(val count: Int) : InAppNotification() {
        override val priority = 1
    }

    data object HiddenVolumeWriteProtection : InAppNotification() {
        override val priority = 3
    }

    /**
     * [reason] is the native error the write actually failed with. It used to
     * be absent and the banner hardcoded "Not enough space in the vault" for
     * every cause, which sent the reporter of #114 looking for a space problem
     * that was not there.
     */
    data class ImportFailed(val reason: ImportFailureReason) : InAppNotification() {
        override val priority = 2
    }

    data object ReadOnlyError : InAppNotification() {
        override val priority = 2
    }

    /**
     * Shown on the Play flavour in place of [SupportDeveloper], where the equivalent of a
     * donation is buying the full version. Never shown to someone who already has it.
     */
    data object GoPremium : InAppNotification() {
        override val priority = 3
    }

    data object AppUpdated : InAppNotification() {
        override val priority    = 3
        override val persistent  = true
    }
}
