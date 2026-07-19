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
    /** Volume is out of clusters - remedy is expanding the vault or freeing space. */
    NO_SPACE,
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

    data object AppUpdated : InAppNotification() {
        override val priority    = 3
        override val persistent  = true
    }
}
