package com.macedonga.visor.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.macedonga.visor.R
import com.macedonga.visor.core.assistant.Assistant
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.settings.GlassesSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where a custom wake word enrolment is. */
sealed interface WakeStep {
    data object Phrase : WakeStep
    data class Take(val index: Int, val recording: Boolean = false, val missed: Boolean = false) : WakeStep
    data object Enrolling : WakeStep
    data class Enrolled(val phrase: String) : WakeStep

    /** [why] is the glasses' own words when they gave any, else [fallback]. */
    data class Failed(val why: String?, val fallback: Int) : WakeStep
}

/**
 * A custom wake word, enrolled on the glasses as the Meta app does it on the device: the phrase, three recordings on
 * the glasses' microphone, and the glasses' answer. The model outlives the screen, so each visit starts over.
 */
@HiltViewModel
class WakeWordModel @Inject constructor(
    private val assistant: Assistant,
    val settings: GlassesSettings,
    @ApplicationContext context: Context,
) : ViewModel() {
    private val prefs = context.getSharedPreferences("wake_word", Context.MODE_PRIVATE)

    private val _enrolled = MutableStateFlow(prefs.getString(ENROLLED, null))

    /** The phrase last enrolled from here. */
    val enrolled: StateFlow<String?> = _enrolled.asStateFlow()

    private val _step = MutableStateFlow<WakeStep>(WakeStep.Phrase)
    val step: StateFlow<WakeStep> = _step.asStateFlow()

    private val _phrase = MutableStateFlow("")
    val phrase: StateFlow<String> = _phrase.asStateFlow()

    private val clips = mutableListOf<ByteArray>()
    private var session: String? = null

    fun enter() {
        clips.clear()
        _step.value = WakeStep.Phrase
    }

    /** Leaving mid-enrolment closes the session, so the glasses wake on the phrase again. */
    fun leave() {
        val open = session ?: return
        session = null
        viewModelScope.launch { assistant.wakeWord.end(open) }
    }

    override fun onCleared() = leave()

    fun setPhrase(text: String) {
        _phrase.value = text.trim()
    }

    fun begin() {
        val text = _phrase.value
        if (text.length < MIN_LETTERS) return
        clips.clear()
        viewModelScope.launch {
            session = assistant.wakeWord.begin(text) ?: run {
                _step.value = WakeStep.Failed(null, R.string.ww_no_link)
                return@launch
            }
            _step.value = WakeStep.Take(0)
        }
    }

    fun record() {
        val take = _step.value as? WakeStep.Take ?: return
        if (take.recording) return
        _step.value = take.copy(recording = true, missed = false)
        viewModelScope.launch {
            val clip = assistant.wakeWord.record()
            if (clip == null || clip.size < MIN_BYTES) {
                _step.value = take.copy(recording = false, missed = true)
                return@launch
            }
            clips += clip
            if (clips.size < TAKES) _step.value = WakeStep.Take(clips.size) else enroll()
        }
    }

    private suspend fun enroll() {
        _step.value = WakeStep.Enrolling
        val text = _phrase.value
        val result = assistant.wakeWord.enroll(text, clips.toList())
        session?.let { assistant.wakeWord.end(it) }
        session = null
        if (result?.success == true) {
            val name = result.wakeword.ifEmpty { text }
            prefs.edit().putString(ENROLLED, name).apply()
            _enrolled.value = name
            _step.value = WakeStep.Enrolled(name)
        } else {
            _step.value = WakeStep.Failed(result?.error?.ifEmpty { null }, R.string.ww_failed_body)
        }
    }

    fun retry() {
        clips.clear()
        _step.value = WakeStep.Phrase
    }

    companion object {
        const val TAKES = 3
        const val MIN_LETTERS = 4
        // A quarter second of 16 kHz 16-bit audio: anything shorter is no phrase.
        private const val MIN_BYTES = 8_000
        private const val ENROLLED = "enrolled"
    }
}

@Composable
fun WakeWordScreen(model: WakeWordModel = hiltViewModel()) {
    val step by model.step.collectAsStateWithLifecycle()
    val phrase by model.phrase.collectAsStateWithLifecycle()
    val back = LocalBack.current
    var editing by remember { mutableStateOf(false) }
    DisposableEffect(model) {
        model.enter()
        onDispose { model.leave() }
    }

    SettingsPage(
        stringResource(R.string.ww_title),
        sheet = {
            if (editing) {
                TextSheet(
                    stringResource(R.string.ww_phrase), phrase, onSave = model::setPhrase, onDismiss = { editing = false },
                    hint = "Hey Jarvis", valid = { it.trim().length >= WakeWordModel.MIN_LETTERS },
                )
            }
        },
    ) {
        item(key = "step") {
            when (val s = step) {
                WakeStep.Phrase -> Column {
                    WakeCard(stringResource(R.string.ww_phrase_title), stringResource(R.string.ww_phrase_body))
                    Column(Modifier.padding(top = 16.dp)) {
                        Rows(
                            row {
                                ValueRow(
                                    stringResource(R.string.ww_phrase), phrase.ifEmpty { stringResource(R.string.settings_not_set) },
                                    onClick = { editing = true }, sub = stringResource(R.string.ww_phrase_short),
                                )
                            },
                        )
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = SIDE).padding(top = 18.dp)) {
                        Spacer(Modifier.weight(1f))
                        ActionPill(stringResource(R.string.ww_continue), model::begin, enabled = phrase.length >= WakeWordModel.MIN_LETTERS)
                    }
                    // The glasses' check (libassistantruntime): one locale, en_US, else the usual engine.
                    if (model.settings.value("assistant_voice_id")?.startsWith("en_US") != true) Footnote(stringResource(R.string.ww_english))
                }
                is WakeStep.Take -> WakeCard(
                    stringResource(R.string.ww_take_title, phrase),
                    when {
                        s.recording -> stringResource(R.string.ww_listening)
                        s.missed -> stringResource(R.string.ww_missed)
                        else -> stringResource(R.string.ww_take, s.index + 1, WakeWordModel.TAKES)
                    },
                ) {
                    ActionPill(stringResource(R.string.ww_record), model::record, enabled = !s.recording)
                }
                WakeStep.Enrolling -> WakeCard(stringResource(R.string.ww_enrolling), null)
                is WakeStep.Enrolled -> WakeCard(stringResource(R.string.ww_done_title), stringResource(R.string.ww_done_body, s.phrase)) {
                    ActionPill(stringResource(R.string.features_restart_done), back)
                }
                is WakeStep.Failed -> WakeCard(stringResource(R.string.ww_failed_title), s.why ?: stringResource(s.fallback)) {
                    ActionPill(stringResource(R.string.hct_retry), model::retry)
                }
            }
        }
    }
}

@Composable
private fun WakeCard(title: String, body: String?, action: (@Composable () -> Unit)? = null) {
    Card {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = Visor.type.heading, color = Visor.colors.textPrimary)
            if (body != null) Text(body, style = Visor.type.body, color = Visor.colors.textSecondary)
            if (action != null) Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Spacer(Modifier.weight(1f))
                action()
            }
        }
    }
}

/** The custom wake word's row on the Meta AI page. */
@Composable
fun WakeWordRow(onClick: () -> Unit, model: WakeWordModel = hiltViewModel()) {
    val enrolled by model.enrolled.collectAsStateWithLifecycle()
    ValueRow(stringResource(R.string.ww_title), enrolled ?: stringResource(R.string.settings_not_set), onClick = onClick)
}
