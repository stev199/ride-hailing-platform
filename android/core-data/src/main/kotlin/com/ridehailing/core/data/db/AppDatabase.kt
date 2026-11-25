package com.ridehailing.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [RideEntity::class, RideFtsEntity::class, com.ridehailing.core.data.outbox.OutboxEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rideDao(): RideDao
    abstract fun outboxDao(): com.ridehailing.core.data.outbox.OutboxDao

    companion object {
        const val NAME = "ride-hailing.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME).build()
    }
}
