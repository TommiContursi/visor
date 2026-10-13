package com.macedonga.visor

import android.app.Application
import com.macedonga.visor.log.EventLog
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
}
