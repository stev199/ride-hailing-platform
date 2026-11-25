package com.ridehailing.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.ridehailing.core.data.db.AppDatabase
import com.ridehailing.core.data.db.RideDao
import com.ridehailing.core.data.outbox.OutboxDao
import com.ridehailing.core.data.prefs.SessionDataStore
import com.ridehailing.core.data.prefs.SettingsDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

/** Injectable no-arg clock lambda for ViewModel constructor injection (tests pin time). */
@javax.inject.Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ClockMs

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase = AppDatabase.build(context)

    @Provides
    fun rideDao(db: AppDatabase): RideDao = db.rideDao()

    @Provides
    fun outboxDao(db: AppDatabase): OutboxDao = db.outboxDao()

    @Provides
    fun placeDao(db: AppDatabase): com.ridehailing.core.data.db.PlaceDao = db.placeDao()

    /** Injectable clock: tests pin time, production gets UTC. */
    @Provides
    @Singleton
    fun clock(): Clock = Clock.systemUTC()

    /** Same clock as a lambda for ViewModel constructor injection (e.g. PlacesViewModel). */
    @Provides
    @Singleton
    @ClockMs
    fun clockMs(clock: Clock): () -> Long = { clock.millis() }
}

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {
    @Provides
    @Singleton
    @SessionDataStore
    fun sessionDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("session") }

    @Provides
    @Singleton
    @SettingsDataStore
    fun settingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }
}
