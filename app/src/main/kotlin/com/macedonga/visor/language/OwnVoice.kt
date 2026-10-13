package com.macedonga.visor.language

import com.macedonga.visor.core.assistant.AssistantConfigStore
import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.media.GlassesConfig
import com.macedonga.visor.core.session.LinkState
import com.macedonga.visor.core.settings.GlassesSettings
import com.macedonga.visor.log.EventLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Meta's switch for its session ("SCI") assistant path, on for English. There the glasses never speak Visor's text. */
const val SESSION_PATH_FLAG = "wearables_ai_phone_call.session_centric_air_infra"

private const val CAPTION_VOICE = "live_caption_voice_id"

/**
 * Answers in the glasses' own voice need the classic assistant path in every language. Each time the glasses connect
 * with that chosen, keep Meta's session flag off: read their configuration once if it was never read, and when the flag
 * is on, write it off and restart their assistant so it takes effect.
 */
fun keepOwnVoicePath(
    scope: CoroutineScope,
    connection: GlassesConnection,
    config: GlassesConfig,
    media: com.macedonga.visor.core.media.MediaImport,
    settings: GlassesSettings,
    store: AssistantConfigStore,
    log: EventLog,
) {
    scope.launch {
        combine(
            connection.state.map { it.link == LinkState.READY }.distinctUntilChanged(),
            store.config.map { it.glassesVoice }.distinctUntilChanged(),
        ) { ready, own -> ready && own }.collectLatest { needed ->
            if (!needed) return@collectLatest
            // Let the link settle; the configuration comes in a bug report, a minute over Bluetooth.
            delay(SETTLE_MS)
            if (config.state.value.params.isEmpty()) {
                // The configuration comes in a bug report of several megabytes: never on top of photos to bring over.
                combine(media.importing, media.captures) { importing, captures -> !importing && captures.none { it.isNew && it.isMedia } }
                    .first { it }
                log.add("Own voice: reading the glasses' configuration once")
                config.read()
                withTimeoutOrNull(READ_MS) { config.state.first { it.params.isNotEmpty() } } ?: return@collectLatest
            }
            val state = config.state.first { it.busy.isEmpty() }
            if (SESSION_PATH_FLAG !in state.params || state.values[SESSION_PATH_FLAG] != true) return@collectLatest
            log.add("Own voice: turning Meta's session path off, so the glasses speak answers in every language")
            config.apply(mapOf(SESSION_PATH_FLAG to false))
            val written = withTimeoutOrNull(WRITE_MS) {
                config.state.first { it.busy.isEmpty() && it.values[SESSION_PATH_FLAG] == false }
            }
            if (written == null) return@collectLatest log.add("Own voice: the glasses did not take the change: ${config.state.value.note}")
            log.add(if (restartGlassesAssistant(settings)) "Own voice: restarting the glasses' assistant to apply it" else "Own voice: restart the glasses to apply it")
        }
    }
}

/**
 * Restart only the glasses' assistant, which reads MobileConfig afresh when it starts. It exits on any change of
 * live_caption_voice_id, so that voice goes to another value and straight back.
 */
suspend fun restartGlassesAssistant(settings: GlassesSettings): Boolean {
    // The value to put back must be the glasses' own, so read them first if this run has not.
    if (CAPTION_VOICE !in settings.state.value.stored) {
        settings.read()
        withTimeoutOrNull(SETTINGS_MS) { settings.state.first { CAPTION_VOICE in it.stored } } ?: return false
    }
    val was = settings.state.value.stored[CAPTION_VOICE].orEmpty()
    val other = if (was == "en_US.mai_03") "en_US.mai_01" else "en_US.mai_03"
    if (!settings.write(CAPTION_VOICE, other)) return false
    delay(1_500)
    return settings.write(CAPTION_VOICE, was)
}

private const val SETTLE_MS = 20_000L
private const val READ_MS = 5 * 60_000L
private const val WRITE_MS = 2 * 60_000L
private const val SETTINGS_MS = 20_000L
