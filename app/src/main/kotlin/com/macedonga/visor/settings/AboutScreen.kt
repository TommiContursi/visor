package com.macedonga.visor.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macedonga.visor.BuildConfig
import com.macedonga.visor.R

/** What this is: Visor's version, the glasses' firmware, and the work it stands on. */
@Composable
fun AboutScreen(model: SettingsViewModel = hiltViewModel()) {
    val glasses by model.connection.state.collectAsStateWithLifecycle()
    SettingsPage(stringResource(R.string.about_title), stringResource(R.string.about_lede), light = 0.35f) {
        item(key = "versions") {
            Rows(
                { ValueRow("Visor", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", onClick = null, sub = if (BuildConfig.DEBUG) stringResource(R.string.about_debug) else null) },
                { ValueRow(stringResource(R.string.glasses_firmware), glasses.info?.build?.let(::firmwareWords) ?: stringResource(R.string.settings_unknown), onClick = null) },
            )
        }
        item(key = "credits") {
            Column {
                SectionLabel(stringResource(R.string.about_credits))
                Rows(
                    *CREDITS.map { (name, licence) -> @Composable { ValueRow(name, licence, onClick = null) } }.toTypedArray(),
                )
                Footnote(stringResource(R.string.about_foot))
            }
        }
    }
}

private val CREDITS = listOf(
    "Inter" to "SIL OFL 1.1",
    "Jetpack Compose, AndroidX" to "Apache 2.0",
    "Filament" to "Apache 2.0",
    "Kotlin, kotlinx" to "Apache 2.0",
    "Dagger Hilt" to "Apache 2.0",
    "FlatBuffers" to "Apache 2.0",
    "Bouncy Castle" to "MIT",
)
