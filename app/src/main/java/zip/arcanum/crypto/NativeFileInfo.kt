package zip.arcanum.crypto

/**
 * One entry of a listing, as the native layer sees it.
 *
 * Built by C++ through JNI NewObject — see the ProGuard rule keeping the
 * constructor. Both listing paths construct it: `jni_ext4.cpp` for ext4 volumes
 * and `jni_files.cpp` for FAT and exFAT, and the second passes [KIND_REGULAR] or
 * [KIND_DIRECTORY] and nothing else, because neither of those filesystems has
 * anything but files and folders.
 *
 * [isDirectory] describes the ENTRY, not what it may point at. A symlink naming a
 * directory has [isDirectory] false and [kind] [KIND_SYMLINK], so deleting,
 * renaming and copying act on the link itself rather than on what is behind it —
 * following there would delete a folder the user never saw. Where following is
 * what the user means, which is opening, [linkTargetIsDirectory] says whether it
 * lands on a folder and the native path layer follows on its own.
 */
data class NativeFileInfo(
    val name: String,
    val path: String,
    val size: Long,
    val isDirectory: Boolean,
    val lastModified: Long,
    /**
     * What the entry itself is: one of the KIND_* values below, whose numbers live
     * in app/src/main/cpp/arcanum_file_kind.h and are checked against these by
     * FileKindSyncTest.
     */
    val kind: Int = KIND_REGULAR,
    /** The path a symlink holds, exactly as it was written; null for anything else. */
    val linkTarget: String? = null,
    /** Whether following the link lands on a directory. False when it is broken. */
    val linkTargetIsDirectory: Boolean = false,
    /** The link names something that is not there, or a ring of links. */
    val linkBroken: Boolean = false,
    /**
     * How many names this file has. One for almost everything; more once a hard
     * link has been made, since that is a second name for one file rather than a
     * second file.
     *
     * Carried because it is the ONLY visible trace a hard link leaves. The second
     * name is not a copy and not a shortcut — it is the file, so there is nothing
     * about it to look at, and a user who has just made one cannot otherwise tell
     * it apart from the copy they were trying to avoid. `ls -l` shows this in its
     * second column for the same reason. Always 1 on FAT and exFAT, which have no
     * such thing.
     */
    val nameCount: Int = 1,
) {
    val isSymlink: Boolean get() = kind == KIND_SYMLINK

    /**
     * Whether opening this entry lands the user in a folder.
     *
     * Deliberately separate from [isDirectory]: this one follows the link and
     * [isDirectory] does not. Opening is the one action where following is what
     * the user means — deleting, renaming and copying a link are about the link.
     */
    val opensAsDirectory: Boolean
        get() = isDirectory || (isSymlink && linkTargetIsDirectory && !linkBroken)

    /**
     * A FIFO, socket or device node. They hold no data — one written into a vault
     * on a desktop is a name with nothing behind it as far as Arcanum is concerned,
     * and offering to open or export it would produce an empty file and no
     * explanation.
     */
    val isSpecial: Boolean get() = kind == KIND_SPECIAL

    companion object {
        const val KIND_REGULAR = 0
        const val KIND_DIRECTORY = 1
        const val KIND_SYMLINK = 2
        const val KIND_SPECIAL = 3
    }
}
