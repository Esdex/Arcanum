package zip.arcanum.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import zip.arcanum.core.database.dao.CalculatorHistoryDao
import zip.arcanum.core.database.dao.ContainerDao
import zip.arcanum.core.database.dao.MediaFileDao
import zip.arcanum.core.database.entities.CalculationEntity
import zip.arcanum.core.database.entities.ContainerEntity
import zip.arcanum.core.database.entities.MediaFileEntity
import zip.arcanum.core.database.entities.MountPointEntity

@Database(
    entities = [
        ContainerEntity::class,
        MediaFileEntity::class,
        CalculationEntity::class,
        MountPointEntity::class
    ],
    version = 14,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    companion object {
        /** Must match the `version` in the @Database annotation above - the debug screen reports it. */
        const val VERSION = 14

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE containers ADD COLUMN prf TEXT NOT NULL DEFAULT '—'")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE containers ADD COLUMN filesystem TEXT NOT NULL DEFAULT '—'")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_files ADD COLUMN description TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE containers ADD COLUMN hasBiometric INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE containers ADD COLUMN unmountOnLock INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE containers ADD COLUMN unmountOnBackground INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_files_containerId ON media_files (containerId)")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE containers ADD COLUMN safUri TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE containers ADD COLUMN keySize INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE containers ADD COLUMN encryptionMode TEXT NOT NULL DEFAULT 'XTS'")
                db.execSQL("ALTER TABLE containers ADD COLUMN blockSize INTEGER NOT NULL DEFAULT 128")
                db.execSQL("ALTER TABLE containers ADD COLUMN formatVersion INTEGER NOT NULL DEFAULT 2")
                db.execSQL("ALTER TABLE containers ADD COLUMN hasBackupHeader INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE containers ADD COLUMN pkcs5Iterations INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE containers ADD COLUMN headerModifiedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE containers ADD COLUMN externalAccessEnabled INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM media_files WHERE rowid NOT IN (SELECT MIN(rowid) FROM media_files GROUP BY containerId, relativePath)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_media_files_containerId_relativePath ON media_files (containerId, relativePath)")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE containers ADD COLUMN usbSaltHash TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * Where a USB vault starts on its drive. 0 means the whole device, which is what
         * every vault created before partitions were supported is, so the default keeps
         * them correct without a data fix (#131).
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE containers ADD COLUMN usbStartByte INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * What the last successful mount used (#148). The two -1 defaults mean auto-detect,
         * which is what every existing vault already did, so no data fix is needed and no
         * vault changes behaviour until it is mounted once with something chosen.
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE containers ADD COLUMN mountHashId INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE containers ADD COLUMN mountAlgorithmId INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE containers ADD COLUMN mountReadOnly INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE containers ADD COLUMN mountProtectHidden INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
    abstract fun containerDao(): ContainerDao
    abstract fun mediaFileDao(): MediaFileDao
    abstract fun calculatorHistoryDao(): CalculatorHistoryDao
}
