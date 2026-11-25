package com.ridehailing.core.data.prefs

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Session + theme persistence on plain JVM (DataStore is file-based, no Android needed).
 * runBlocking over runTest: DataStore requires a real dispatcher for its own IO actor.
 */
class StoresTest {
    private lateinit var tmp: TemporaryFolder
    private lateinit var scope: CoroutineScope
    private lateinit var sessionFile: File
    private lateinit var settingsFile: File

    @Before
    fun setUp() {
        tmp = TemporaryFolder()
        tmp.create()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        // Do NOT pre-create the files: DataStore creates/closes them itself.
        sessionFile = File(tmp.root, "session_test.preferences_pb")
        settingsFile = File(tmp.root, "settings_test.preferences_pb")
    }

    @After
    fun tearDown() {
        scope.cancel()
        tmp.delete()
    }

    private fun sessionStore() = SessionStore(
        PreferenceDataStoreFactory.create(scope = scope) { sessionFile },
    )

    private fun themeStore() = ThemeStore(
        PreferenceDataStoreFactory.create(scope = scope) { settingsFile },
    )

    @Test
    fun session_roundTrip() = runBlocking {
        val store = sessionStore()
        assertNull(store.sessionFlow.first().accessToken)

        store.save(
            Session(
                accessToken = "acc",
                refreshToken = "ref",
                userId = "u1",
                phone = "+213600000000",
            ),
        )
        val s = store.sessionFlow.first()
        assertEquals("acc", s.accessToken)
        assertEquals("ref", s.refreshToken)
        assertEquals("u1", s.userId)
        assertEquals("+213600000000", s.phone)

        store.clear()
        val cleared = store.sessionFlow.first()
        assertNull(cleared.accessToken)
        assertNull(cleared.refreshToken)
    }

    @Test
    fun theme_saveReadClear() = runBlocking {
        val store = themeStore()
        assertNull(store.themePreference.first())

        store.setTheme(ThemeStore.THEME_DARK)
        assertEquals("dark", store.themePreference.first())

        // back to "follow system" = remove the key
        store.setTheme(null)
        assertNull(store.themePreference.first())

        store.setLanguage("ar")
        assertEquals("ar", store.language.first())
        store.setLanguage(null)
        assertNull(store.language.first())
    }

    @Test
    fun session_isolation_betweenFiles() = runBlocking {
        // writing settings must not touch the session file (separate stores/files)
        val sessions = sessionStore()
        val themes = themeStore()
        sessions.save(Session("a", "b", "c", "d"))
        themes.setTheme("dark")
        assertEquals("a", sessions.sessionFlow.first().accessToken)
        assertTrue(sessionFile.readBytes().isNotEmpty())
    }
}
