package com.ridehailing.driver

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/** Driver app (P2-1c): boots the Hilt SingletonComponent graph (Room/DataStore/outbox). */
@HiltAndroidApp
class DriverApplication : Application()
