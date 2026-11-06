package dev.gyro.core.database

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import dev.gyro.core.database.dao.PlaybackHistoryDao
import dev.gyro.core.database.entity.PlaybackHistoryEntity

@Database(
    entities = [
        PlaybackHistoryEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class GyroDatabase : RoomDatabase() {
    abstract fun playbackHistoryDao(): PlaybackHistoryDao

    companion object {
        private const val DB_NAME = "gyro.db"

        fun create(context: Context): GyroDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                GyroDatabase::class.java,
                DB_NAME
            ).fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
