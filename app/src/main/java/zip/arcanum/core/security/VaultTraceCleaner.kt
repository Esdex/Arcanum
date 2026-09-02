package zip.arcanum.core.security

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import zip.arcanum.ArcanumApp
import zip.arcanum.arcanum.gallery.ThumbnailManager
import zip.arcanum.core.database.dao.ContainerDao
import zip.arcanum.core.database.dao.MediaFileDao
import zip.arcanum.core.database.entities.ContainerEntity
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything the app holds about a vault other than the vault's own row, in one place (#134).
 *
 * It exists because there were two ways for a vault to stop existing and they did not clean
 * the same things. Deleting one from the list cleared the media index and the thumbnails;
 * panic mode went straight to the DAO and cleared neither, so a wipe left behind the names
 * and paths of every file that had been inside the vault it had just erased. Both paths call
 * this now, so a new artefact is added in one place and cannot be forgotten in the other.
 *
 * "Clean" here means what the audit meant: nothing left that names the vault, its location,
 * or anything that was inside it.
 */
@Singleton
class VaultTraceCleaner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val containerDao: ContainerDao,
    private val mediaDao: MediaFileDao,
    private val thumbnailManager: ThumbnailManager,
    private val biometricCryptoManager: BiometricCryptoManager
) {

    /**
     * Removes every trace of one vault. The vault's own row is the caller's business - it is
     * deleted differently on each path, and some callers need the entity afterwards.
     *
     * Safe to call for a vault that has none of these.
     */
    suspend fun purge(id: String) {
        val entity = containerDao.getContainerById(id)

        // The media index is not a cache: its rows carry the names and paths of the files
        // that were inside.
        mediaDao.deleteAllForContainer(id)
        thumbnailManager.clearCache(id)
        clearWaveforms(id)

        // The encrypted password blob, its IV, and the keyfile URIs - which name files
        // outside the vault.
        val keyfileUris = biometricCryptoManager.loadKeyfileUris(id)
        biometricCryptoManager.deleteCredentials(id)

        if (entity != null) releaseSafPermission(entity)
        releaseKeyfilePermissions(id, keyfileUris)

        // It quotes the vault's path in "Source: ...". Always cleared rather than only when
        // it belongs to this vault: it holds one mount, the last one, and telling whose it
        // is means matching paths - which is worse than losing a debug log.
        clearMountLog()
    }

    /**
     * Removes what belongs to no vault on the list any more.
     *
     * The fix above only cleans up as a vault is removed; anything stranded by a version that
     * did not clean up stays stranded, and on a phone that has been in use that is most of
     * it. Run once at startup, it costs one query and one directory listing.
     *
     * Persisted URI grants are deliberately NOT swept here. Once the vault is gone the app no
     * longer knows which URI was its, and a tree grant that looks unclaimed may be the one a
     * live vault's document URI depends on - releasing that would lock the user out of a
     * vault that still exists. Grants are released at the moment the vault is removed, where
     * the URI is known; ones stranded before this build stay.
     */
    suspend fun purgeOrphans() {
        val live = containerDao.getAllContainersOnce()
        val ids  = live.map { it.id }

        runCatching { mediaDao.deleteOrphans(ids) }

        runCatching {
            File(context.cacheDir, "arcanum_thumbs").listFiles()?.forEach { dir ->
                if (dir.isDirectory && dir.name !in ids) dir.deleteRecursively()
            }
        }

        /* Biometric credentials of vaults that are gone: an encrypted password, its IV, and
           the URIs of the keyfiles it was unlocked with. Found by measuring a panic wipe -
           it clears the credentials of every vault it knows about, and two entries were
           still there afterwards, belonging to vaults forgotten long before. */
        biometricCryptoManager.knownContainerIds()
            .filterNot { it in ids }
            .forEach { orphan ->
                val uris = biometricCryptoManager.loadKeyfileUris(orphan)
                biometricCryptoManager.deleteCredentials(orphan)
                releaseKeyfilePermissions(orphan, uris)
            }

        val liveKeys = ids.map { waveformVaultKey(it) }.toSet()
        runCatching {
            context.cacheDir.listFiles()?.forEach { f ->
                val n = f.name
                if (!n.startsWith("wf_") || !n.endsWith(".dat")) return@forEach
                val body = n.drop(3).dropLast(4)
                // No vault segment at all: the old naming, unattributable, so it goes.
                val vaultKey = body.substringBefore('_', missingDelimiterValue = "")
                if (vaultKey.isEmpty() || vaultKey !in liveKeys) f.delete()
            }
        }
    }

    /** The saved mount log, which quotes the path of the vault it mounted. */
    fun clearMountLog() {
        runCatching { File(context.filesDir, ArcanumApp.MOUNT_LOG_FILE).delete() }
    }

    /** Java and native crash reports. A stack trace can carry a container path in a message. */
    fun clearCrashLogs() {
        runCatching { File(context.filesDir, ArcanumApp.CRASH_DIR_NAME).deleteRecursively() }
    }

    /**
     * Waveforms of audio that was played out of a vault. The contents are encrypted; the file
     * names are not, and they outlive the vault.
     *
     * Files are named `wf_<vault>_<file>.dat`, so a vault's own can be found. Anything in the
     * older `wf_<hash>.dat` form cannot be attributed to a vault at all - the hash mixes the
     * vault, the path and the size together - so those go wholesale the first time any vault
     * is purged. They cost one waveform recalculation each.
     */
    fun clearWaveforms(id: String) {
        val prefix = "wf_${waveformVaultKey(id)}_"
        runCatching {
            context.cacheDir.listFiles()?.forEach { f ->
                val n = f.name
                if (!n.startsWith("wf_") || !n.endsWith(".dat")) return@forEach
                if (n.startsWith(prefix) || !n.drop(3).dropLast(4).contains('_')) f.delete()
            }
        }
    }

    /** Every waveform, whoever it belonged to. */
    fun clearAllWaveforms() {
        runCatching {
            context.cacheDir.listFiles()?.forEach { f ->
                if (f.name.startsWith("wf_") && f.name.endsWith(".dat")) f.delete()
            }
        }
    }

    /**
     * Hands back the grant taken when the vault was added from a picker. Without this Android
     * keeps a persisted permission naming a file the app no longer knows anything about -
     * `takePersistableUriPermission` was called in five places and released in none.
     *
     * Kept if another vault is the same file: two rows can point at one URI.
     */
    private suspend fun releaseSafPermission(entity: ContainerEntity) {
        if (entity.safUri.isEmpty()) return
        val stillUsed = containerDao.getAllContainersOnce()
            .any { it.id != entity.id && it.safUri == entity.safUri }
        if (stillUsed) return
        release(entity.safUri)
    }

    /** The same for keyfiles, which are shareable between vaults and so are checked first. */
    private suspend fun releaseKeyfilePermissions(id: String, uris: List<String>) {
        if (uris.isEmpty()) return
        val stillUsed = containerDao.getAllContainersOnce()
            .filter { it.id != id }
            .flatMap { biometricCryptoManager.loadKeyfileUris(it.id) }
            .toSet()
        uris.filterNot { it in stillUsed }.forEach { release(it) }
    }

    /**
     * Releases exactly the flags that are actually held. Asking to release a flag that was
     * never taken throws, and a keyfile is taken read-only while a vault file is taken for
     * writing - so the two cannot share one constant.
     */
    private fun release(uri: String) {
        runCatching {
            val parsed = Uri.parse(uri)
            val held = context.contentResolver.persistedUriPermissions
                .firstOrNull { it.uri == parsed } ?: return
            var flags = 0
            if (held.isReadPermission)  flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (held.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            if (flags != 0) context.contentResolver.releasePersistableUriPermission(parsed, flags)
        }
    }

    companion object {
        /**
         * The vault's half of a waveform's file name. Kept here rather than in the player so
         * that the thing that writes the name and the thing that deletes it cannot drift.
         */
        fun waveformVaultKey(containerId: String): String =
            java.lang.Integer.toHexString(containerId.hashCode())
    }
}
