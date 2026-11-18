package dev.gyro.core.database

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.gyro.core.database.dao.PlaybackHistoryDao
import dev.gyro.core.database.entity.PlaybackHistoryEntity

@Database(
    entities = [
        PlaybackHistoryEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class GyroDatabase : RoomDatabase() {
    abstract fun playbackHistoryDao(): PlaybackHistoryDao

    companion object {
        private const val DB_NAME = "gyro.db"

        val MIGRATION_1_2: Migration = Migration(1, 2) { connection ->
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_playback_history_updatedAt ON playback_history(updatedAt)"
            )
        }

        fun create(context: Context): GyroDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                GyroDatabase::class.java,
                DB_NAME
            )
                .addMigrations(MIGRATION_1_2)
                .setDriver(BundledSQLiteDriver())
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
