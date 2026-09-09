package zip.arcanum.core.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import zip.arcanum.BuildConfig
import zip.arcanum.core.database.dao.ContainerDao
import zip.arcanum.core.database.entities.ContainerEntity
import zip.arcanum.core.security.AppPreferences
import zip.arcanum.core.security.VaultDisplayPrefs
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Carrying the app's settings to another phone (#63).
 *
 * **What travels:** the preferences, the way the vault list is arranged, and - if asked for -
 * the list of vaults itself.
 *
 * **What does not, and why each one:**
 *
 * - **The PIN.** A file that carries the way in is a key, and setting a PIN again takes a
 *   minute. The same goes for the panic PIN.
 * - **Panic mode.** Esdex's call, and the right one: what it does is bound to this phone and
 *   to the vaults on it, and a file describing a duress plan is a dangerous document to
 *   leave lying about.
 * - **Biometric unlock.** Not a choice: those credentials are wrapped by keys that cannot
 *   leave the phone's keystore, which is the whole point of them (#89).
 * - **The disguise, the clocks, the shuffle seed** - see [AppPreferences.BACKUP_SKIP].
 * - **Anything derived**: the media index, thumbnails, waveforms, logs. All of it is rebuilt
 *   from the vaults themselves.
 *
 * **A vault that travels is a row, not a volume.** The file itself stays where it is, and on
 * another phone its path may not exist and its SAF permission certainly does not - so a
 * restored vault has to be pointed at its file once, by hand. A vault on a USB drive is the
 * exception: it is found by the hash of its header salt, which is a property of the volume
 * rather than of the phone, so it comes back whole.
 */
@Singleton
class SettingsBackup @Inject constructor(
    private val prefs: AppPreferences,
    private val displayPrefs: VaultDisplayPrefs,
    private val containerDao: ContainerDao
) {

    /** What went in, so the screen can say it rather than claim success in the abstract. */
    /**
     * [hiddenProtected] is how many of those vaults are set to protect a hidden volume. It is
     * not written anywhere; the screen needs it to warn before a file with no password is
     * made, because such a file says in plain text that a hidden volume exists.
     */
    data class Summary(val settings: Int, val vaults: Int, val hiddenProtected: Int = 0)

    sealed interface Restored {
        data class Success(val settings: Int, val vaults: Int, val vaultsSkipped: Int) : Restored
        /** The password was wrong, or the file is not what it says it is. */
        data object WrongPassword : Restored
        data class TooNew(val format: Int) : Restored
        data object Malformed : Restored
    }

    @Serializable
    private data class Entry(val t: String, val v: String)

    @Serializable
    private data class Payload(
        val settings: Map<String, Entry> = emptyMap(),
        val display: Map<String, Entry> = emptyMap(),
        val vaults: List<Vault> = emptyList()
    )

    /**
     * A vault as a backup carries it.
     *
     * Deliberately not [ContainerEntity] itself: that class is a database row and changes
     * with the schema, while this is a file format other builds have to read. Fields are
     * written by name and anything missing takes its default, so a file from an older
     * version restores without ceremony.
     */
    @Serializable
    private data class Vault(
        val id: String,
        val name: String,
        val path: String,
        val safUri: String = "",
        val size: Long = 0L,
        val algorithm: String = "",
        val prf: String = "",
        val filesystem: String = "",
        val createdAt: Long = 0L,
        val isFavorite: Boolean = false,
        val unmountOnLock: Boolean = false,
        val unmountOnBackground: Boolean = false,
        val externalAccessEnabled: Boolean = false,
        val mountHashId: Int = -1,
        val mountAlgorithmId: Int = -1,
        val mountReadOnly: Boolean = false,
        val mountProtectHidden: Boolean = false,
        val keySize: Int = 0,
        val encryptionMode: String = "XTS",
        val blockSize: Int = 128,
        val formatVersion: Int = 2,
        val hasBackupHeader: Boolean = true,
        val pkcs5Iterations: Int = 0,
        val usbSaltHash: String = "",
        val usbStartByte: Long = 0L,
        /* Carried on purpose: without it a restored vault has nothing to check a file
           against, and pointing it at the wrong one would be accepted in silence - which is
           exactly what happened the first time this was tried. */
        val volumeSaltHash: String = ""
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ── Writing ───────────────────────────────────────────────────────────────

    /** What a backup would contain right now - for the screen that offers to write one. */
    suspend fun preview(includeVaults: Boolean): Summary {
        val rows = if (includeVaults) containerDao.getAllContainersOnce() else emptyList()
        return Summary(
            settings = prefs.exportAll().count { it.key !in prefs.BACKUP_SKIP } + displayPrefs.exportAll().size,
            vaults   = rows.size,
            hiddenProtected = rows.count { it.mountProtectHidden }
        )
    }

    suspend fun export(includeVaults: Boolean, password: CharArray?): Pair<ByteArray, Summary> {
        val settings = prefs.exportAll()
            .filterKeys { it !in prefs.BACKUP_SKIP }
            .mapValues { (_, value) -> value.toEntry() }
        val display  = displayPrefs.exportAll().mapValues { (_, value) -> value.toEntry() }
        val vaults   = if (includeVaults) containerDao.getAllContainersOnce().map { it.toBackup() }
                       else emptyList()

        val payload = json.encodeToString(Payload(settings, display, vaults))
        val bytes   = BackupCodec.encode(payload, BuildConfig.VERSION_CODE, password)
        return bytes to Summary(settings.size, vaults.size, vaults.count { it.mountProtectHidden })
    }

    // ── Reading ───────────────────────────────────────────────────────────────

    /** Whether this file will ask for a password, so the screen can ask before it reads. */
    fun needsPassword(bytes: ByteArray): Boolean? = BackupCodec.isEncrypted(bytes)

    suspend fun import(bytes: ByteArray, password: CharArray?): Restored {
        val payloadBytes = when (val opened = BackupCodec.decode(bytes, password)) {
            is BackupCodec.Opened.Payload       -> opened.json.toByteArray()
            is BackupCodec.Opened.WrongPassword -> return Restored.WrongPassword
            is BackupCodec.Opened.TooNew        -> return Restored.TooNew(opened.format)
            is BackupCodec.Opened.Malformed     -> return Restored.Malformed
        }

        val payload = runCatching { json.decodeFromString<Payload>(String(payloadBytes)) }
            .getOrNull() ?: return Restored.Malformed

        prefs.importAll(payload.settings.mapValues { (_, e) -> e.toValue() })
        displayPrefs.importAll(payload.display.mapValues { (_, e) -> e.toValue() })

        var added = 0
        var skipped = 0
        if (payload.vaults.isNotEmpty()) {
            val existing = containerDao.getAllContainersOnce()
            payload.vaults.forEach { vault ->
                /* Never replace a vault that is already here: this phone's row knows where
                   the file actually is, and the backup's does not. Adding what is missing is
                   the whole job. */
                val already = existing.any { row ->
                    /* By id FIRST, and it is not belt and braces: insertContainer replaces on
                       a primary-key conflict, so a vault that has since been moved - same id,
                       new path - would have had this phone's row overwritten by the file's
                       stale one, and the vault would read as missing. Then by what identifies
                       a volume rather than a row. */
                    row.id == vault.id ||
                        when {
                            vault.usbSaltHash.isNotEmpty() -> row.usbSaltHash == vault.usbSaltHash
                            vault.safUri.isNotEmpty()      -> row.safUri == vault.safUri
                            else                           -> row.path.isNotEmpty() && row.path == vault.path
                        }
                }
                if (already) skipped++ else { containerDao.insertContainer(vault.toEntity()); added++ }
            }
        }
        return Restored.Success(payload.settings.size, added, skipped)
    }

    // ── Plumbing ──────────────────────────────────────────────────────────────

    private fun Any.toEntry(): Entry = when (this) {
        is Boolean -> Entry("b", toString())
        is Int     -> Entry("i", toString())
        is Long    -> Entry("l", toString())
        is String  -> Entry("s", this)
        else       -> Entry("s", toString())
    }

    private fun Entry.toValue(): Any = when (t) {
        "b"  -> v.toBooleanStrictOrNull() ?: false
        "i"  -> v.toIntOrNull() ?: 0
        "l"  -> v.toLongOrNull() ?: 0L
        else -> v
    }

    private fun ContainerEntity.toBackup() = Vault(
        id = id, name = name, path = path, safUri = safUri, size = size,
        algorithm = algorithm, prf = prf, filesystem = filesystem, createdAt = createdAt,
        isFavorite = isFavorite, unmountOnLock = unmountOnLock,
        unmountOnBackground = unmountOnBackground, externalAccessEnabled = externalAccessEnabled,
        mountHashId = mountHashId, mountAlgorithmId = mountAlgorithmId,
        mountReadOnly = mountReadOnly, mountProtectHidden = mountProtectHidden,
        keySize = keySize, encryptionMode = encryptionMode, blockSize = blockSize,
        formatVersion = formatVersion, hasBackupHeader = hasBackupHeader,
        pkcs5Iterations = pkcs5Iterations, usbSaltHash = usbSaltHash, usbStartByte = usbStartByte,
        volumeSaltHash = volumeSaltHash
    )

    private fun Vault.toEntity() = ContainerEntity(
        id = id, name = name, path = path, size = size, algorithm = algorithm, prf = prf,
        filesystem = filesystem, createdAt = createdAt, lastAccessedAt = 0L,
        isFavorite = isFavorite,
        /* Three things a restored row must not claim: that the volume is open, that a
           finger opens it, and that its files may be read by other apps. The first is true
           only of a running app, the second of a keystore that stayed on the other phone,
           and the third is a consent given on that phone, to the apps on it - it is asked
           for again here rather than assumed. */
        isMounted = false, hasBiometric = false, externalAccessEnabled = false,
        unmountOnLock = unmountOnLock, unmountOnBackground = unmountOnBackground,
        mountHashId = mountHashId,
        mountAlgorithmId = mountAlgorithmId, mountReadOnly = mountReadOnly,
        mountProtectHidden = mountProtectHidden, safUri = safUri, keySize = keySize,
        encryptionMode = encryptionMode, blockSize = blockSize, formatVersion = formatVersion,
        hasBackupHeader = hasBackupHeader, pkcs5Iterations = pkcs5Iterations,
        headerModifiedAt = 0L, usbSaltHash = usbSaltHash, usbStartByte = usbStartByte,
        volumeSaltHash = volumeSaltHash
    )

}
