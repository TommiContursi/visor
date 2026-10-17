package com.macedonga.visor.features

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import com.macedonga.visor.HearingTestKey
import com.macedonga.visor.R
import com.macedonga.visor.core.assistant.CorrectionTone
import com.macedonga.visor.core.assistant.Ear
import com.macedonga.visor.core.assistant.Hearing
import com.macedonga.visor.core.assistant.HearingMode
import com.macedonga.visor.core.assistant.HearingRefusal
import com.macedonga.visor.core.assistant.HearingRun
import com.macedonga.visor.core.assistant.HearingState
import com.macedonga.visor.core.assistant.Thresholds
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.gallery.RoundIcon
import com.macedonga.visor.settings.ActionPill
import com.macedonga.visor.settings.BAR
import com.macedonga.visor.settings.Card
import com.macedonga.visor.settings.Footnote
import com.macedonga.visor.settings.LocalBack
import com.macedonga.visor.settings.LocalHost
import com.macedonga.visor.settings.PillsRow
import com.macedonga.visor.settings.Rows
import com.macedonga.visor.settings.SectionLabel
import com.macedonga.visor.settings.SettingsHost
import com.macedonga.visor.settings.SettingsPage
import com.macedonga.visor.settings.SliderRow
import com.macedonga.visor.settings.ToggleRow
import com.macedonga.visor.settings.TopBar
import com.macedonga.visor.settings.ValueRow
import com.macedonga.visor.settings.row
import com.macedonga.visor.stage.LightLook
import com.macedonga.visor.stage.LocalStage
import com.macedonga.visor.stage.Mood
import com.macedonga.visor.stage.STAGE_ASPECT
import com.macedonga.visor.stage.StageLight
import com.macedonga.visor.stage.StageSpot
import com.macedonga.visor.stage.lookOf
import com.macedonga.visor.ui.VisorIcons
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Hearing correction: the glasses as a hearing aid, fitted by a hearing test on the glasses. On top, where it stands
 * and the one button that changes that; then its sound once fitted; the test; and the switch that lets these glasses
 * run it at all.
 */
@Composable
fun HearingCorrectionScreen(open: (NavKey) -> Unit, model: FeaturesModel = hiltViewModel()) {
    val state by model.features.state.collectAsStateWithLifecycle()
    val hearing by model.hearing.state.collectAsStateWithLifecycle()
    val tested by model.features.tested.collectAsStateWithLifecycle()
    val feature = Feature.HEARING_CORRECTION
    val standing = state.standings[feature] ?: Standing.UNKNOWN
    val allowed = standing == Standing.ON
    // The glasses' word when they give it, else what the last test here left.
    val fitted = hearing.fitted ?: tested

    SettingsPage(stringResource(R.string.feature_hc), stringResource(R.string.hc_lede)) {
        item(key = "now") { CorrectionNow(hearing, allowed, fitted, feature in state.restart, model.hearing, model.features::restarted) }
        if (fitted) {
            item(key = "sound") {
                val host = LocalHost.current
                val tone = hearing.correction ?: CorrectionTone()
                val locked = !host.ready || !allowed
                val range = Hearing.MIN_TONE.toFloat()..Hearing.MAX_TONE.toFloat()
                val steps = Hearing.MAX_TONE - Hearing.MIN_TONE - 1
                val signed: (Float) -> String = { v -> v.roundToInt().let { if (it > 0) "+$it" else "$it" } }
                val left = stringResource(R.string.hc_balance_left)
                val right = stringResource(R.string.hc_balance_right)
                val centre = stringResource(R.string.hc_balance_centre)
                Column {
                    SectionLabel(stringResource(R.string.cf_sound))
                    Rows(
                        row {
                            SliderRow(stringResource(R.string.hc_amplification), tone.volume.toFloat(), range, steps, signed, { model.hearing.tuneCorrection(tone.copy(volume = it.roundToInt())) }, locked = locked)
                        },
                        row {
                            SliderRow(stringResource(R.string.hc_sharpness), tone.sharpness.toFloat(), range, steps, signed, { model.hearing.tuneCorrection(tone.copy(sharpness = it.roundToInt())) }, locked = locked)
                        },
                        row {
                            SliderRow(
                                stringResource(R.string.hc_balance), tone.balance.toFloat(), range, steps,
                                { v -> v.roundToInt().let { if (it < 0) "$left ${-it}" else if (it > 0) "$right $it" else centre } },
                                { model.hearing.tuneCorrection(tone.copy(balance = it.roundToInt())) }, locked = locked,
                            )
                        },
                        row {
                            PillsRow(
                                stringResource(R.string.cf_style),
                                listOf(false to stringResource(R.string.hc_style_moderate), true to stringResource(R.string.hc_style_extra)),
                                selected = tone.noisy,
                                onPick = { model.hearing.tuneCorrection(tone.copy(noisy = it)) },
                                locked = locked,
                            )
                        },
                    )
                    Footnote(stringResource(R.string.hc_sound_note))
                }
            }
        }
        item(key = "test") {
            val host = LocalHost.current
            Column {
                SectionLabel(stringResource(R.string.hct_title))
                Rows(
                    row {
                        ValueRow(
                            stringResource(if (fitted) R.string.hc_test_again else R.string.hc_test_take), null,
                            onClick = { open(HearingTestKey) },
                            locked = !host.ready || !allowed,
                        )
                    },
                )
            }
        }
        item(key = "allow") {
            val host = LocalHost.current
            Column {
                SectionLabel(stringResource(R.string.cf_allow_section))
                Rows(
                    row {
                        ToggleRow(
                            stringResource(R.string.hc_allow), allowed,
                            onChange = { model.features.set(feature, it) },
                            sub = stringResource(if (allowed) R.string.cf_allow_on else R.string.hc_allow_off),
                            locked = !host.ready, busy = standing == Standing.SENDING,
                            problem = if (standing == Standing.REFUSED) stringResource(R.string.features_refused) else null,
                        )
                    },
                )
            }
        }
    }
}

/** Where hearing correction stands, in a word and a line, with the one button that changes it once there is a profile. */
@Composable
private fun CorrectionNow(hearing: HearingState, allowed: Boolean, fitted: Boolean, restart: Boolean, control: Hearing, restarted: () -> Unit) {
    val host = LocalHost.current
    val connectFirst = stringResource(R.string.settings_locked)
    val correcting = hearing.mode == HearingMode.CORRECTION || hearing.mode == HearingMode.CORRECTION_NARROW
    val run = if (correcting) hearing.run else HearingRun.OFF
    val paused = run == HearingRun.PAUSED || run == HearingRun.PAUSED_BY_USER
    val (title, sub) = when {
        !allowed -> R.string.cf_now_locked to R.string.cf_now_locked_sub
        restart -> R.string.features_restart_title to R.string.features_restart_line
        !fitted -> R.string.hc_now_untested to R.string.hc_now_untested_sub
        run == HearingRun.ON -> R.string.cf_now_on to R.string.hc_now_on_sub
        paused -> R.string.cf_now_paused to R.string.cf_paused
        // USECASE_ENABLE_FAILURE: the glasses' audio chip took the left ear's profile and refused the right's.
        hearing.refusal == HearingRefusal.ENABLE_FAILED -> R.string.cf_now_off to R.string.hc_refused_chip
        hearing.refusal != null -> R.string.cf_now_off to R.string.cf_refused
        else -> R.string.cf_now_off to R.string.hc_now_off_sub
    }
    val warn = hearing.refusal != null && run != HearingRun.ON && allowed && fitted && !restart
    Card {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            FeatureTile(VisorIcons.HearingAid, lit = run == HearingRun.ON, size = 52)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(title), style = Visor.type.heading, color = Visor.colors.textPrimary)
                Text(stringResource(sub), style = Visor.type.label, color = if (warn) Visor.colors.signal else Visor.colors.textSecondary)
            }
            if (allowed && restart) {
                Spacer(Modifier.width(12.dp))
                ActionPill(stringResource(R.string.features_restart_done), restarted)
            } else if (fitted) {
                Spacer(Modifier.width(12.dp))
                val (label, act) = when {
                    run == HearingRun.ON -> R.string.cf_stop to control::stop
                    paused -> R.string.cf_resume to control::resume
                    else -> R.string.cf_start_now to { control.startCorrection() }
                }
                ActionPill(
                    stringResource(label),
                    {
                        if (!host.ready) host.notice.show(connectFirst)
                        else {
                            host.haptics.toggle()
                            act()
                        }
                    },
                    quiet = run == HearingRun.ON,
                    enabled = allowed,
                )
            }
        }
    }
}

/** Where the hearing test is. */
sealed interface TestStep {
    data object Who : TestStep

    /** The answer means hearing correction is not for this person. */
    data class NotFor(val why: Int) : TestStep
    data object Ready : TestStep
    data object Starting : TestStep
    data class Tone(val index: Int, val level: Int) : TestStep
    data object Fitting : TestStep
    data object Done : TestStep
    data class Failed(val why: Int) : TestStep
}

/**
 * The Meta app's self-fitting, step by step: the hearing app in its setup mode, four tones an ear (1, 2 and 4 kHz,
 * then 1 kHz again, which is the one that counts), each from 40 dB HL in 5 dB steps until barely heard, then the
 * thresholds to the glasses, which make the profile and switch to hearing correction.
 */
@HiltViewModel
class HearingTestModel @Inject constructor(private val hearing: Hearing, private val features: Features) : ViewModel() {
    private val _step = MutableStateFlow<TestStep>(TestStep.Who)
    val step: StateFlow<TestStep> = _step.asStateFlow()

    private val levels = IntArray(TONES.size) { START_DBHL }
    private var testing = false

    // One tone request at a time, 600 ms after the last answer, as the Meta app sends them; a newer ask replaces a
    // waiting one. Between tones the old one is stopped first, or the glasses refuse the next.
    private val tones = Mutex()
    private var asked = 0
    private var moving: Job? = null

    fun answer(rating: Int) {
        _step.value = when (rating) {
            RATING_MILD -> TestStep.Ready
            RATING_NONE -> TestStep.NotFor(R.string.hct_not_none)
            RATING_SEVERE -> TestStep.NotFor(R.string.hct_not_severe)
            else -> TestStep.NotFor(R.string.hct_not_implant)
        }
    }

    fun begin() {
        _step.value = TestStep.Starting
        viewModelScope.launch {
            hearing.startCorrection(test = true)
            if (!hearing.awaitRunning(HearingMode.CORRECTION_SETUP, START_MS)) {
                _step.value = TestStep.Failed(R.string.hct_failed_start)
                return@launch
            }
            testing = true
            levels.fill(START_DBHL)
            show(0)
        }
    }

    /** Louder or quieter by one step, and play it again. */
    fun nudge(up: Boolean) {
        val s = _step.value as? TestStep.Tone ?: return
        if (moving?.isActive == true) return
        val level = (s.level + if (up) STEP_DBHL else -STEP_DBHL).coerceIn(Hearing.MIN_DBHL, Hearing.MAX_DBHL)
        if (level != s.level) show(s.index, level)
    }

    /** Can't hear it: the loudest, played again. */
    fun loudest() {
        val s = _step.value as? TestStep.Tone ?: return
        if (moving?.isActive == true || s.level == Hearing.MAX_DBHL) return
        show(s.index, Hearing.MAX_DBHL)
    }

    fun next() {
        val s = _step.value as? TestStep.Tone ?: return
        if (moving?.isActive == true) return
        levels[s.index] = s.level
        moving = viewModelScope.launch {
            silence(s.index)
            if (s.index + 1 < TONES.size) show(s.index + 1) else fit()
        }
    }

    fun retry() {
        _step.value = TestStep.Ready
    }

    private fun show(index: Int, level: Int = START_DBHL) {
        _step.value = TestStep.Tone(index, level)
        val ask = ++asked
        viewModelScope.launch {
            tones.withLock {
                if (ask != asked) return@withLock
                val (ear, hz) = TONES[index]
                if (hearing.playTone(ear, hz, level)) delay(BETWEEN_MS) else _step.value = TestStep.Failed(R.string.hct_failed_tone)
            }
        }
    }

    /** Stop [index]'s tone, waited for; a refusal to stop is no failure. */
    private suspend fun silence(index: Int) {
        asked++
        tones.withLock {
            val (ear, hz) = TONES[index]
            if (hearing.playTone(ear, hz, levels[index], on = false)) delay(BETWEEN_MS)
        }
    }

    private fun fit() {
        _step.value = TestStep.Fitting
        viewModelScope.launch {
            // Tone 1 is practice; tone 4, 1 kHz again, is the one sent.
            fun ear(from: Int) = Thresholds(at1k = levels[from + 3], at2k = levels[from + 1], at4k = levels[from + 2])
            if (!hearing.fit(ear(0), ear(4))) {
                _step.value = TestStep.Failed(R.string.hct_failed_fit)
                return@launch
            }
            testing = false
            features.hearingTested()
            val now = hearing.state.value
            if (now.mode != HearingMode.CORRECTION || now.run != HearingRun.ON) {
                hearing.stop()
                hearing.awaitStopped()
                hearing.startCorrection()
                hearing.awaitRunning(HearingMode.CORRECTION, START_MS)
            }
            _step.value = TestStep.Done
        }
    }

    /** Leaving mid-test stops the hearing app, as the Meta app does. */
    override fun onCleared() {
        if (testing) hearing.stop()
    }

    companion object {
        const val RATING_NONE = 0
        const val RATING_MILD = 1
        const val RATING_SEVERE = 2
        const val RATING_IMPLANT = 3
        const val TONES_PER_EAR = 4
        private const val START_DBHL = 40
        private const val STEP_DBHL = 5
        private const val BETWEEN_MS = 600L
        private const val START_MS = 10_000L
        private val TONES = listOf(Ear.LEFT, Ear.RIGHT).flatMap { ear -> listOf(1000, 2000, 4000, 1000).map { ear to it } }
    }
}

@Composable
fun HearingTestScreen(model: HearingTestModel = hiltViewModel()) = SettingsHost(seeThrough = true) {
    val step by model.step.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val strip = top + BAR + TEST_STAGE
        with(density) { TestStage(step, (maxWidth / STAGE_ASPECT).toPx(), strip.toPx(), (top + BAR + TEST_STAGE / 2).toPx()) }
        Box(Modifier.fillMaxSize().padding(top = strip).background(Visor.colors.canvas))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = strip, bottom = bottom + 32.dp)) {
            TestStepContent(step, model)
        }
        TopBar(stringResource(R.string.hct_title))
    }
}

/** The pair under its light with its temples open, turned to the ear being tested; the light breathes while a tone plays. */
@Composable
private fun TestStage(step: TestStep, fullPx: Float, stripPx: Float, centrePx: Float) {
    val stage = LocalStage.current
    val colors = Visor.colors
    StageSpot(closed = false, liveliness = 0.3f) { folding(fullPx, stripPx, 1f, TEST_SCALE, centrePx) }
    val ear = (step as? TestStep.Tone)?.let { if (it.index < HearingTestModel.TONES_PER_EAR) Ear.LEFT else Ear.RIGHT }
    StageLight(
        if (ear != null) LightLook(colors.lightNeutral, colors.lightWarm, level = 1f, spread = 1f, breathSeconds = 1.6f, breathDepth = 0.35f)
        else lookOf(Mood.CONNECTED, colors),
    )
    LaunchedEffect(ear) {
        val yaw = when (ear) {
            Ear.LEFT -> LEFT_EAR_YAW
            Ear.RIGHT -> RIGHT_EAR_YAW
            null -> 0f
        }
        stage.yaw.animateTo(yaw, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessVeryLow))
    }
    // The next screen gets the pair facing ahead again.
    LaunchedEffect(Unit) {
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) { stage.yaw.animateTo(0f, tween(400)) }
        }
    }
}

@Composable
private fun TestStepContent(step: TestStep, model: HearingTestModel) {
    val back = LocalBack.current
    when (val s = step) {
        TestStep.Who -> Column {
            StepCard(stringResource(R.string.hct_who_title), stringResource(R.string.hct_who_body))
            SectionLabel(stringResource(R.string.hct_question), Modifier.padding(top = 24.dp))
            Rows(
                *listOf(
                    HearingTestModel.RATING_NONE to R.string.hct_rating_none,
                    HearingTestModel.RATING_MILD to R.string.hct_rating_mild,
                    HearingTestModel.RATING_SEVERE to R.string.hct_rating_severe,
                    HearingTestModel.RATING_IMPLANT to R.string.hct_rating_implant,
                ).map { (rating, words) -> row { ValueRow(stringResource(words), null, onClick = { model.answer(rating) }) } }.toTypedArray(),
            )
        }
        is TestStep.NotFor -> StepCard(stringResource(R.string.hct_not_title), stringResource(s.why)) {
            ActionPill(stringResource(R.string.features_restart_done), back)
        }
        TestStep.Ready -> StepCard(stringResource(R.string.hct_ready_title), stringResource(R.string.hct_ready_body)) {
            ActionPill(stringResource(R.string.hct_begin), model::begin)
        }
        TestStep.Starting -> StepCard(stringResource(R.string.hct_starting), null)
        is TestStep.Tone -> ToneCard(s, model)
        TestStep.Fitting -> StepCard(stringResource(R.string.hct_fitting), null)
        TestStep.Done -> StepCard(stringResource(R.string.hct_done_title), stringResource(R.string.hct_done_body)) {
            ActionPill(stringResource(R.string.features_restart_done), back)
        }
        is TestStep.Failed -> StepCard(stringResource(R.string.hct_failed_title), stringResource(s.why)) {
            ActionPill(stringResource(R.string.hct_retry), model::retry)
        }
    }
}

/** The strip the pair stands in under the bar during the test, and how large it is there. */
private val TEST_STAGE = 230.dp
private const val TEST_SCALE = 0.95f

// The pair rests at -24 degrees; these show the left temple, then the right, to the viewer.
private const val LEFT_EAR_YAW = -46f
private const val RIGHT_EAR_YAW = 70f

@Composable
private fun StepCard(title: String, body: String?, actions: (@Composable () -> Unit)? = null) {
    Card {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = Visor.type.heading, color = Visor.colors.textPrimary)
            if (body != null) Text(body, style = Visor.type.body, color = Visor.colors.textSecondary)
            if (actions != null) Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Spacer(Modifier.weight(1f))
                actions()
            }
        }
    }
}

@Composable
private fun ToneCard(s: TestStep.Tone, model: HearingTestModel) {
    val perEar = HearingTestModel.TONES_PER_EAR
    val last = s.index == perEar * 2 - 1
    Column {
        Card {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(if (s.index < perEar) R.string.hct_left else R.string.hct_right), style = Visor.type.label, color = Visor.colors.textSecondary)
                Text(stringResource(R.string.hct_tone, s.index % perEar + 1, perEar), style = Visor.type.heading, color = Visor.colors.textPrimary)
                Text(stringResource(R.string.hct_tone_body), style = Visor.type.body, color = Visor.colors.textSecondary)
                Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    RoundIcon(VisorIcons.Minus, stringResource(R.string.hct_quieter), { model.nudge(up = false) }, size = 64.dp, background = Visor.colors.control)
                    Spacer(Modifier.width(32.dp))
                    RoundIcon(VisorIcons.Plus, stringResource(R.string.hct_louder), { model.nudge(up = true) }, size = 64.dp, background = Visor.colors.control)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionPill(stringResource(R.string.hct_cant_hear), model::loudest, quiet = true)
                    Spacer(Modifier.weight(1f))
                    ActionPill(stringResource(if (last) R.string.hct_finish else R.string.hct_next), model::next)
                }
            }
        }
        if (s.level == Hearing.MAX_DBHL) Footnote(stringResource(R.string.hct_loudest_note))
    }
}
