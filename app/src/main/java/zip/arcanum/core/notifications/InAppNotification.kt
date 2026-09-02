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

/**
 * One thing worth telling the user, as data. How loud it is, how long it stays and what
 * colour it wears are not here - they are one table in NotificationSpec.kt, so that adding
 * a notification is a decision about wording rather than about styling (#135).
 *
 * [bannerKey] is what makes two of the same thing one thing: a second notification with a
 * key already in the queue replaces it instead of queueing behind it.
 */
sealed class InAppNotification {
    open val bannerKey: String get() = this::class.simpleName ?: ""

    /**
     * An ext4 vault was opened whose superblock says the last session that wrote to
     * it did not finish - the app killed, the battery gone, a drive pulled (#142).
     *
     * Deliberately not an error. The vault is mounted and every file in it is
     * readable and writable; what a cut-short write leaves behind is bookkeeping a
     * check tidies, and it is left to the user whether to bother. Raised once, when
     * the vault is opened, because the first write clears the flag.
     */
    data object VaultNeedsCheck : InAppNotification()

    /**
     * A USB-hosted vault finished unmounting and the drive can be unplugged.
     *
     * Its own notification because the user has a physical action to take, and for this
     * vault kind forgetting it risks data rather than tidiness: the drive's own write
     * cache cannot be flushed on demand. Sticky for the same reason - a drive still in
     * the socket cannot be allowed to time out unseen.
     */
    data class UsbSafeToRemove(
        val vaultId: String,
        val vaultName: String
    ) : InAppNotification()

    /**
     * A paste where items did not make it. Until this existed a failed paste looked
     * exactly like a successful one - silence either way (#129).
     */
    data class FilesPasteFailed(val failed: Int, val total: Int) : InAppNotification()

    /** A move into the folder the items are already in: correct, and worth saying. */
    data object FilesAlreadyHere : InAppNotification()

    /**
     * A second name was made for something (#128). Its own notification rather than
     * reusing the paste one, because nothing was copied and the vault has not got
     * any fuller - saying "pasted" would describe the wrong thing happening to the
     * user's space.
     *
     * [kind] exists because the promise is not the same for both. A file gets a hard
     * link and "the same file in both places" is true of it word for word - one
     * inode, two names, and no way for either to stop working. A folder gets a
     * symbolic one, which is a path rather than the folder itself and CAN go dead if
     * the folder is moved or removed. Saying the file sentence over a folder would
     * promise something the app does not give.
     */
    data class FilesLinked(val count: Int, val kind: LinkedKind) : InAppNotification()

    /** What was linked, since files and folders are not linked the same way. */
    enum class LinkedKind { FILES, FOLDERS, MIXED }

    /** Tapping the hero icon for details on a vault that is not open. */
    data object DetailsNeedMount : InAppNotification()

    /** Tapping the lock with nothing to unlock with - a prompt, not a failure. */
    data object MountNeedsCredentials : InAppNotification()

    /**
     * A file picker came back after the app had locked itself, so the import or export it
     * was going to start was refused. Shown when the user unlocks and returns, since that
     * is the first moment they can see anything at all.
     */
    data object OperationRefusedLocked : InAppNotification()

    data class VaultError(
        val vaultId: String,
        val message: String
    ) : InAppNotification()

    data class ExportSuccess(
        val fileName: String
    ) : InAppNotification()

    data class VaultAdded(
        val fileName: String
    ) : InAppNotification()

    data class VaultAlreadyExists(
        val fileName: String
    ) : InAppNotification()

    data object VaultInvalidFile : InAppNotification()

    data class VaultAddError(
        val message: String
    ) : InAppNotification()

    data object SupportDeveloper : InAppNotification()

    data object DateUpdated : InAppNotification()

    /**
     * A donation address went to the clipboard. Android 13 and up show their own
     * clipboard confirmation, so this is only raised below that - see DonationsScreen.
     */
    data class AddressCopied(val label: String) : InAppNotification()

    /** A control that the applied camouflage has taken out of the user's hands. */
    data object DisguiseAlreadyApplied : InAppNotification()

    data class FileRenamed(val newName: String) : InAppNotification()

    /**
     * [leftBehind] counts items a copy or a move could not take with it and did not
     * turn into something else: a special file, or a link going where nothing can hold
     * one - into another vault, or onto FAT (#168).
     */
    data class FilesPasted(
        val count: Int,
        val leftBehind: Int = 0,
        /** Names already taken that the user chose to leave alone (#169). */
        val skipped: Int = 0
    ) : InAppNotification()

    /** See [FilesPasted] for [leftBehind] and [skipped]. */
    data class FilesMoved(
        val count: Int,
        val destinationName: String,
        val leftBehind: Int = 0,
        val skipped: Int = 0
    ) : InAppNotification()

    data class FilesDeleted(val count: Int) : InAppNotification()

    data class FolderCreated(val name: String) : InAppNotification()

    /** [skipped] counts names that were already taken and left alone at the user's word (#157). */
    data class FilesImported(val count: Int, val skipped: Int = 0) : InAppNotification()

    /**
     * An import the user stopped (#158). Its own notification rather than a count of what
     * landed, because "12 files imported" after asking for it to stop reads as the app
     * having ignored the request. What did land stays in the vault; the file being written
     * at the time does not.
     */
    data class ImportCancelled(val imported: Int) : InAppNotification()

    /**
     * [skipped] counts items an export could not carry out of the vault at all - a
     * link that leads nowhere, a special file, a folder link that leads back into
     * what is already being exported. [duplicates] counts files that left as more
     * than one copy, which is what a second name for one file becomes outside,
     * where nothing can hold a link (#167).
     */
    data class FilesExported(
        val count: Int,
        val skipped: Int = 0,
        val duplicates: Int = 0,
        /** Items that did not come out whole and were left outside as ".part" (#170). */
        val failed: Int = 0
    ) : InAppNotification()

    data object HiddenVolumeWriteProtection : InAppNotification()

    /**
     * [reason] is the native error the write actually failed with. It used to
     * be absent and the banner hardcoded "Not enough space in the vault" for
     * every cause, which sent the reporter of #114 looking for a space problem
     * that was not there.
     */
    data class ImportFailed(val reason: ImportFailureReason) : InAppNotification()

    data object ReadOnlyError : InAppNotification()

    /**
     * Shown on the Play flavour in place of [SupportDeveloper], where the equivalent of a
     * donation is buying the full version. Never shown to someone who already has it.
     */
    data object GoPremium : InAppNotification()

    data object AppUpdated : InAppNotification()
}
