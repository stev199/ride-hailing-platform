package com.ridehailing.rider

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/** Rider app (P2-1c): boots the Hilt SingletonComponent graph (Room/DataStore/outbox). */
@HiltAndroidApp
class RiderApplication : Application()
