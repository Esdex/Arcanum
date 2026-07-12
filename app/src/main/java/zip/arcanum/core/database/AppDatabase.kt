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
    version = 10,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    companion object {
        const val VERSION = 10

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
    }
    abstract fun containerDao(): ContainerDao
    abstract fun mediaFileDao(): MediaFileDao
    abstract fun calculatorHistoryDao(): CalculatorHistoryDao
}
