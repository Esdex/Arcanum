package zip.arcanum.core.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "containers")
data class ContainerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val path: String,
    val size: Long,
    val algorithm: String,
    @ColumnInfo(defaultValue = "—") val prf: String = "—",
    @ColumnInfo(defaultValue = "—") val filesystem: String = "—",
    val createdAt: Long,
    val lastAccessedAt: Long,
    val isFavorite: Boolean = false,
    val isMounted: Boolean = false,
    @ColumnInfo(defaultValue = "0") val hasBiometric: Boolean = false,
    @ColumnInfo(defaultValue = "0") val unmountOnLock: Boolean = false,
    @ColumnInfo(defaultValue = "0") val unmountOnBackground: Boolean = false,
    @ColumnInfo(defaultValue = "0") val externalAccessEnabled: Boolean = false,
    /*
     * What the last successful mount of this vault used (#148). Remembered so the next
     * mount does not have to be told again - and so a fingerprint unlock, which shows no
     * options screen at all, mounts the vault the same way the user set it up rather than
     * falling back to the defaults. -1 in either algorithm means auto-detect, which is what
     * every vault that predates this did, so the defaults leave them unchanged.
     *
     * Only preferences live here. The password, the PIM, the keyfiles and the hidden
     * volume's password are secrets and stay in the biometric store.
     */
    @ColumnInfo(defaultValue = "-1") val mountHashId: Int = -1,
    @ColumnInfo(defaultValue = "-1") val mountAlgorithmId: Int = -1,
    @ColumnInfo(defaultValue = "0")  val mountReadOnly: Boolean = false,
    @ColumnInfo(defaultValue = "0")  val mountProtectHidden: Boolean = false,
    @ColumnInfo(defaultValue = "") val safUri: String = "",
    @ColumnInfo(defaultValue = "0") val keySize: Int = 0,
    @ColumnInfo(defaultValue = "XTS") val encryptionMode: String = "XTS",
    @ColumnInfo(defaultValue = "128") val blockSize: Int = 128,
    @ColumnInfo(defaultValue = "2") val formatVersion: Int = 2,
    @ColumnInfo(defaultValue = "1") val hasBackupHeader: Boolean = true,
    @ColumnInfo(defaultValue = "0") val pkcs5Iterations: Int = 0,
    @ColumnInfo(defaultValue = "0") val headerModifiedAt: Long = 0L,
    /**
     * SHA-256 of the volume header salt, for a vault that occupies a whole USB device
     * (#95). Empty for every file-hosted vault, which is what distinguishes the two.
     *
     * The salt identifies the VOLUME, not the hardware: it is 64 bytes of plaintext at
     * offset 0, unique per volume and readable without the password. Nothing about the
     * device itself works for this - the device name is a bus address that changes on
     * replug, VID/PID identify a model rather than a unit, and serial numbers are often
     * absent. Recreating the volume on the same stick correctly reads as a different
     * vault. Hashed rather than stored raw so the database holds no bytes that appear
     * verbatim on the drive.
     */
    @ColumnInfo(defaultValue = "") val usbSaltHash: String = "",

    /**
     * Byte offset of the volume on its drive: 0 for a whole device, otherwise the start
     * of the partition holding it (#131).
     *
     * A hint, not an identity. Repartitioning a drive moves the volume without changing
     * its salt, so mounting falls back to searching the partitions for a matching
     * fingerprint; this only spares that search in the ordinary case.
     */
    @ColumnInfo(defaultValue = "0") val usbStartByte: Long = 0L,
)
