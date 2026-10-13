package com.macedonga.visor.connection

import androidx.annotation.StringRes
import com.macedonga.visor.R
import com.macedonga.visor.core.connection.GlassesState
import com.macedonga.visor.core.identity.Diagnosis
import com.macedonga.visor.core.session.LinkState

/** The connection state, in one line of plain words. */
@StringRes
fun connectionWords(state: GlassesState, hasBluetoothPermission: Boolean = true): Int = when {
    !hasBluetoothPermission -> R.string.connection_no_bluetooth_permission
    state.target == null -> R.string.connection_idle
    state.diagnosis == Diagnosis.EVICTED -> R.string.connection_key_refused
    state.link == LinkState.READY -> R.string.connection_ready
    state.link == LinkState.LOST -> R.string.connection_not_answering
    state.link == LinkState.DISCONNECTED -> R.string.connection_idle
    else -> R.string.connection_reaching
}
