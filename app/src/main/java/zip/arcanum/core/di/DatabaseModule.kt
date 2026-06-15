package zip.arcanum.core.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import zip.arcanum.core.database.AppDatabase
import zip.arcanum.core.database.dao.CalculatorHistoryDao
import zip.arcanum.core.database.dao.ContainerDao
import zip.arcanum.core.database.dao.MediaFileDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "arcanum.db")
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7)
            .build()

    @Provides
    fun provideContainerDao(db: AppDatabase): ContainerDao = db.containerDao()

    @Provides
    fun provideMediaFileDao(db: AppDatabase): MediaFileDao = db.mediaFileDao()

    @Provides
    fun provideCalculatorHistoryDao(db: AppDatabase): CalculatorHistoryDao = db.calculatorHistoryDao()
}
