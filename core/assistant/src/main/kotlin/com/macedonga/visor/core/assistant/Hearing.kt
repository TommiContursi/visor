package com.macedonga.visor.core.assistant

import com.macedonga.visor.core.connection.GlassesConnection
import com.macedonga.visor.core.protocol.Wire
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.session.DataXMessage
import com.macedonga.visor.core.session.Responder
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The glasses' hearing service: conversation focus reports its state here and takes its style and volume. */
const val SVC_HEARING = 31337

/** Where conversation focus is on the glasses. */
enum class HearingRun { UNKNOWN, ON, PAUSED, PAUSED_BY_USER, OFF }

/** Why the glasses paused or ended it, as they report it. */
enum class HearingCause(val number: Long) {
    NONE(0), PHONE_CALL(1), MUSIC(2), LIVE_STREAMING(3), STREAMING_AUDIO(4), CAPTURING_VIDEO(5), THERMAL(7), SHUTDOWN(8), TIMEOUT(9), CRASH(10);

    companion object {
        fun of(n: Long) = entries.firstOrNull { it.number == n } ?: NONE
    }
}

/** Why the glasses refused to start it. */
enum class HearingRefusal { UNKNOWN, NUX_REQUIRED, FREE_TRIAL_ENDED, SUBSCRIPTION_EXPIRED, GEOGATED, BT_REQUIRED, OUTSIDE_GRACE_PERIOD, INVALID_DEVICE, USER_SETTINGS_FAILED, NO_ACCESS, ENABLE_FAILED }

/** The two styles the glasses take: Standard brings voices closer, Boosted further. */
enum class FocusStyle(val preset: Int) { STANDARD(1), BOOSTED(2) }

data class HearingState(
    val run: HearingRun = HearingRun.UNKNOWN,
    val cause: HearingCause = HearingCause.NONE,
    val style: FocusStyle? = null,
    /** How much louder the voice is, -2 to 2. */
    val volume: Int? = null,
    val refusal: HearingRefusal? = null,
    /** When the glasses last said anything here. */
    val at: Long = 0,
)

/**
 * Conversation focus run from the phone, as the Meta app runs it. Starting and stopping go to the companion service
 * (svc28: StartApp 2000 and StopApp 2002 for app 22, HEARING, with the mode and a session id as intent data); style
 * and volume are a UserSetting (type 15) on the hearing service, which reports back its AppState (type 1) and why a
 * start was refused (type 7).
 */
class Hearing(private val connection: GlassesConnection) : Responder {
    private val _state = MutableStateFlow(HearingState())
    val state: StateFlow<HearingState> = _state.asStateFlow()

    private val _notes = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val notes: SharedFlow<String> = _notes.asSharedFlow()

    // The hearing channel the glasses opened, answered on its mirror; until then Visor opens its own.
    @Volatile
    private var channel: Int? = null

    override suspend fun respond(message: DataXMessage, connection: DataXConnection) {
        channel = message.info.channelId
        val f = runCatching { Wire.parse(message.payload) }.getOrNull() ?: return
        when (message.info.messageType) {
            MT_APP_STATE -> {
                val setting = if (f.has(5)) Wire.parse(f.bytes(5)) else null
                _state.update {
                    it.copy(
                        run = when (f.varint(1)) {
                            1L -> HearingRun.ON
                            2L -> HearingRun.PAUSED
                            3L -> HearingRun.OFF
                            4L -> HearingRun.PAUSED_BY_USER
                            else -> HearingRun.UNKNOWN
                        },
                        cause = HearingCause.of(f.varint(2)),
                        style = setting?.takeIf { s -> s.has(13) }?.let { s -> FocusStyle.entries.firstOrNull { st -> st.preset.toLong() == s.varint(13) } } ?: it.style,
                        volume = setting?.takeIf { s -> s.has(2) }?.varint(2)?.toInt() ?: it.volume,
                        refusal = if (f.varint(1) == 1L) null else it.refusal,
                        at = System.currentTimeMillis(),
                    )
                }
                note("Conversation focus: ${_state.value.run.name.lowercase()}" + if (f.has(2)) " (${_state.value.cause.name.lowercase()})" else "")
            }
            MT_ACTIVATION_FAILURE -> {
                val why = HearingRefusal.entries.getOrElse(f.varint(1).toInt()) { HearingRefusal.UNKNOWN }
                _state.update { it.copy(refusal = why, run = HearingRun.OFF, at = System.currentTimeMillis()) }
                note("Conversation focus refused: ${why.name.lowercase()}")
            }
            MT_USER_SETTING_FAILURE -> note("Conversation focus did not take the style or volume")
            else -> note("Hearing: message type ${message.info.messageType}")
        }
    }

    /** Start conversation focus now, in [style] when given. */
    fun start(style: FocusStyle?) {
        val data = intent("requestedMode", MODE_FOCUS) + intent("hearingTrigger", TRIGGER_PHONE) +
            intent("hearingSessionId", UUID.randomUUID().toString()) +
            (style?.let { intent("enhancementPreset", it.preset) } ?: ByteArray(0))
        _state.update { it.copy(refusal = null) }
        connection.submit { it.sendOnService(SVC_COMPANION, MT_START_APP, Wire.fieldVarint(1, APP_HEARING) + data) }
        note("Starting conversation focus")
    }

    fun stop() {
        connection.submit { it.sendOnService(SVC_COMPANION, MT_STOP_APP, Wire.fieldVarint(1, APP_HEARING)) }
        note("Stopping conversation focus")
    }

    /** Carry on after a pause. */
    fun resume() = sendHearing(MT_RESUME, Wire.fieldBytes(1, UUID.randomUUID().toString().encodeToByteArray()))

    /** The style and how much louder the voice is (-2 to 2): applied at once while it runs, kept for next time otherwise. */
    fun tune(style: FocusStyle, volume: Int) {
        val v = volume.coerceIn(MIN_VOLUME, MAX_VOLUME)
        sendHearing(
            MT_USER_SETTING,
            Wire.fieldVarintUnsigned(2, v.toLong()) + Wire.fieldBytes(10, "1".encodeToByteArray()) +
                Wire.fieldBytes(11, UUID.randomUUID().toString().encodeToByteArray()) + Wire.fieldVarint(13, style.preset.toLong()),
        )
        _state.update { it.copy(style = style, volume = v) }
    }

    private fun sendHearing(type: Int, payload: ByteArray) {
        val open = channel
        connection.submit { mux ->
            if (open != null) mux.send(DataXConnection.mirror(open), payload, messageType = type)
            else mux.sendOnService(SVC_HEARING, type, payload)
        }
    }

    /** One SNIntentData: the key, and the value in the field its type takes (3 an int, 5 bytes). */
    private fun intent(key: String, value: Int): ByteArray =
        Wire.fieldBytes(3, Wire.fieldBytes(1, key.encodeToByteArray()) + Wire.fieldVarint(3, value.toLong()))

    private fun intent(key: String, value: String): ByteArray =
        Wire.fieldBytes(3, Wire.fieldBytes(1, key.encodeToByteArray()) + Wire.fieldBytes(5, value.encodeToByteArray()))

    private fun note(text: String) {
        _notes.tryEmit(text)
    }

    companion object {
        const val MIN_VOLUME = -2
        const val MAX_VOLUME = 2
        const val DEFAULT_VOLUME = 1
        private const val SVC_COMPANION = 28
        private const val MT_START_APP = 2000
        private const val MT_STOP_APP = 2002
        private const val APP_HEARING = 22L
        private const val MODE_FOCUS = 1
        private const val TRIGGER_PHONE = 9
        private const val MT_APP_STATE = 1
        private const val MT_ACTIVATION_FAILURE = 7
        private const val MT_USER_SETTING = 15
        private const val MT_USER_SETTING_FAILURE = 18
        private const val MT_RESUME = 20
    }
}
