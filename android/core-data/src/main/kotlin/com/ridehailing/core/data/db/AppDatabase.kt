package com.ridehailing.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        RideEntity::class,
        RideFtsEntity::class,
        com.ridehailing.core.data.outbox.OutboxEntity::class,
        PlaceEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rideDao(): RideDao
    abstract fun outboxDao(): com.ridehailing.core.data.outbox.OutboxDao
    abstract fun placeDao(): PlaceDao

    companion object {
        const val NAME = "ride-hailing.db"

        /** v2 (P2-2): places table (offline-first search history). Forward-fix policy (D33). */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `places` (" +
                        "`placeId` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`lat` REAL NOT NULL, " +
                        "`lng` REAL NOT NULL, " +
                        "`source` TEXT NOT NULL, " +
                        "`lastUsedMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`placeId`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_places_lastUsedMs` ON `places` (`lastUsedMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_places_source` ON `places` (`source`)")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
