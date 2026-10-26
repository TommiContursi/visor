package fi.tomba.companion

import android.app.Application
import android.util.Log
import com.meta.wearable.dat.core.Wearables

class CompanionApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Wearables.initialize(this).onFailure { error, _ ->
            Log.e("Companion", "Meta DAT initialization: ${error.description}")
        }
    }
}
