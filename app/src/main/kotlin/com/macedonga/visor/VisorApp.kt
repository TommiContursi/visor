package com.macedonga.visor

import android.app.Application
import com.macedonga.visor.log.EventLog
import com.macedonga.visor.update.Updates
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * It injects the [EventLog] only so the log exists from the first moment and records a session the
 * service runs while no screen is open.
 */
@HiltAndroidApp
class VisorApp : Application() {

    @Inject
    lateinit var eventLog: EventLog

    @Inject
    lateinit var updates: Updates

    override fun onCreate() {
        super.onCreate()
        updates.start()
    }
}
