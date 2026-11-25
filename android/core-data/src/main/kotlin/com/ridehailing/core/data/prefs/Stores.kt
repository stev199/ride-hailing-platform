package com.ridehailing.core.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** DataStore<Preferences> qualifiers: session tokens vs user settings live in separate files. */
@javax.inject.Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class SessionDataStore

@javax.inject.Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class SettingsDataStore

@javax.inject.Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class FareDataStore

data class Session(
    val accessToken: String?,
    val refreshToken: String?,
    val userId: String?,
    val phone: String?,
)

/**
 * Auth session persistence (P2-4 will write real tokens; the store exists so the offline
 * shell can already boot with and observe a session). Refresh token lives here - same trust
 * model as the admin shell (D67), XSS surface is Compose-only string rendering.
 */
@Singleton
class SessionStore @Inject constructor(@SessionDataStore private val dataStore: DataStore<Preferences>) {
    private object Keys {
        val access = stringPreferencesKey("session.access_token")
        val refresh = stringPreferencesKey("session.refresh_token")
        val userId = stringPreferencesKey("session.user_id")
        val phone = stringPreferencesKey("session.phone")
    }

    val sessionFlow: Flow<Session> = dataStore.data.map { p ->
        Session(
            accessToken = p[Keys.access],
            refreshToken = p[Keys.refresh],
            userId = p[Keys.userId],
            phone = p[Keys.phone],
        )
    }

    suspend fun save(session: Session) {
        dataStore.edit { p ->
            p[Keys.access] = session.accessToken.orEmpty()
            p[Keys.refresh] = session.refreshToken.orEmpty()
            p[Keys.userId] = session.userId.orEmpty()
            p[Keys.phone] = session.phone.orEmpty()
        }
    }

    suspend fun clear() {
        dataStore.edit { it.clear() }
    }
}

/** User settings persisted now so the P2-4 settings UI only needs the screen, not storage. */
@Singleton
class ThemeStore @Inject constructor(@SettingsDataStore private val dataStore: DataStore<Preferences>) {
    companion object {
        /** null = follow the system; else "light" | "dark". */
        val THEME_SYSTEM: String? = null
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
    }

    private val themeKey = stringPreferencesKey("settings.theme")
    private val languageKey = stringPreferencesKey("settings.language")

    /** Emits the persisted theme choice, null when the user follows the system. */
    val themePreference: Flow<String?> = dataStore.data.map { it[themeKey] }

    /** "en" | "fr" | "ar" once set; null until the language screen lands. */
    val language: Flow<String?> = dataStore.data.map { it[languageKey] }

    suspend fun setTheme(value: String?) {
        dataStore.edit { p ->
            if (value == null) p.remove(themeKey) else p[themeKey] = value
        }
    }

    suspend fun setLanguage(value: String?) {
        dataStore.edit { p ->
            if (value == null) p.remove(languageKey) else p[languageKey] = value
        }
    }
}
