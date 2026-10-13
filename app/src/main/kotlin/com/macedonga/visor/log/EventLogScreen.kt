package com.macedonga.visor.log

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macedonga.visor.core.media.MediaImport
import com.macedonga.visor.ui.Line
import com.macedonga.visor.ui.PlainScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class EventLogViewModel @Inject constructor(val log: EventLog, val media: MediaImport) : ViewModel()

@Composable
fun EventLogScreen(model: EventLogViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val lines by model.log.lines.collectAsStateWithLifecycle()

    PlainScreen("Event log") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { copy(context, model.log.text()) }) { Text("Copy all") }
            OutlinedButton(onClick = { model.log.clear() }) { Text("Clear") }
        }
        OutlinedButton(onClick = model.media::requestBugReport) { Text("Get a bug report from the glasses") }
        if (lines.isEmpty()) Line("Nothing has happened yet.", secondary = true)
        for (line in lines) Line(line)
    }
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("Visor event log", text))
}
