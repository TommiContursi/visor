package com.macedonga.visor.notify

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the phone's notifications go to the glasses at all. On unless the person turns it off. */
@Singleton
class NotifyChoice @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("notify", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun set(on: Boolean) {
        _enabled.value = on
        prefs.edit().putBoolean(KEY, on).apply()
    }

    private companion object {
        const val KEY = "mirror"
    }
}
