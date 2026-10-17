package com.macedonga.visor.features

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import com.macedonga.visor.ConversationFocusKey
import com.macedonga.visor.FlagsKey
import com.macedonga.visor.HearingCorrectionKey
import com.macedonga.visor.R
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.media.ConfigState
import com.macedonga.visor.core.media.GlassesConfig
import com.macedonga.visor.core.settings.GlassesSettings
import com.macedonga.visor.gallery.RoundIcon
import com.macedonga.visor.settings.ActionPill
import com.macedonga.visor.settings.Card
import com.macedonga.visor.settings.Footnote
import com.macedonga.visor.settings.KeyLine
import com.macedonga.visor.settings.LocalHost
import com.macedonga.visor.settings.NavRow
import com.macedonga.visor.settings.Rows
import com.macedonga.visor.settings.SIDE
import com.macedonga.visor.settings.SectionLabel
import com.macedonga.visor.settings.SettingsPage
import com.macedonga.visor.settings.SheetTitle
import com.macedonga.visor.settings.Spinner
import com.macedonga.visor.settings.VisorSheet
import com.macedonga.visor.settings.VisorSwitch
import com.macedonga.visor.settings.timeWords
import com.macedonga.visor.ui.VisorIcons
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class FeaturesModel @Inject constructor(
    val features: Features,
    val config: GlassesConfig,
    val settings: GlassesSettings,
    val hearing: com.macedonga.visor.core.assistant.Hearing,
) : ViewModel()

/**
 * What Meta keeps off on these glasses, each with where it stands. A feature's row says what it does while it is off
 * and how to use it once it is on; held, it opens what Visor changes for it.
 */
@Composable
fun FeaturesScreen(open: (NavKey) -> Unit, model: FeaturesModel = hiltViewModel()) {
    val state by model.features.state.collectAsStateWithLifecycle()
    val config by model.config.state.collectAsStateWithLifecycle()
    var details by remember { mutableStateOf<Feature?>(null) }

    SettingsPage(
        stringResource(R.string.features_title),
        stringResource(R.string.features_lede),
        actions = {
            if (state.busy) Spinner(Modifier.padding(horizontal = 13.dp))
            else if (state.configRead) RoundIcon(VisorIcons.Refresh, stringResource(R.string.features_check), { model.features.check() })
        },
        sheet = {
            details?.let { f ->
                FeatureSheet(
                    f, state.standings[f] ?: Standing.UNKNOWN, on = state.wanted[f] == true, config = config, settings = model.settings,
                    toggle = { model.features.set(f, it) }, onDismiss = { details = null },
                )
            }
        },
    ) {
        if (!state.configRead) item(key = "read") { ReadCard(state.busy) { model.features.check() } }
        if (state.restart.isNotEmpty()) item(key = "restart") { RestartCard(state.restart) { model.features.restarted() } }
        item(key = "features") {
            Column {
                SectionLabel(stringResource(R.string.features_section), Modifier.padding(top = if (!state.configRead || state.restart.isNotEmpty()) 28.dp else 0.dp))
                Rows(
                    *Feature.entries.map { f ->
                        @Composable {
                            FeatureRow(
                                f, state.standings[f] ?: Standing.UNKNOWN, on = state.wanted[f] == true, waits = f in state.restart,
                                configRead = state.configRead, open = open, toggle = { model.features.set(f, it) }, details = { details = f },
                            )
                        }
                    }.toTypedArray(),
                )
                state.problem?.let { Footnote(stringResource(R.string.features_problem, it), Visor.colors.signal) }
                Footnote(
                    listOfNotNull(
                        stringResource(R.string.features_note),
                        state.readAt?.let { stringResource(R.string.features_read_at, timeWords(it)) },
                    ).joinToString(" "),
                )
            }
        }
        if (OutOfReach.entries.isNotEmpty()) {
            item(key = "out") {
                Column {
                    SectionLabel(stringResource(R.string.features_out_section))
                    Rows(*OutOfReach.entries.map { o -> @Composable { OutRow(o) } }.toTypedArray())
                    Footnote(stringResource(R.string.features_out_note))
                }
            }
        }
        item(key = "flags") {
            Column(Modifier.padding(top = 20.dp)) {
                NavRow(VisorIcons.Flag, stringResource(R.string.settings_flags), stringResource(R.string.features_flags_sub), onClick = { open(FlagsKey) })
            }
        }
    }
}

/**
 * A feature: its tile lit while it is on, its name, and a line that says what it does, how to use it once it is on, or
 * what it is waiting for. A tap turns it on or off (or opens its page); held, it opens what Visor changes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FeatureRow(
    feature: Feature,
    standing: Standing,
    on: Boolean,
    waits: Boolean,
    configRead: Boolean,
    open: (NavKey) -> Unit,
    toggle: (Boolean) -> Boolean,
    details: () -> Unit,
) {
    val host = LocalHost.current
    val colors = Visor.colors
    val connectFirst = stringResource(R.string.settings_locked)
    val readFirst = stringResource(R.string.features_read_first)
    val locked = !host.ready || (feature.needsConfig && !configRead)
    val tap: () -> Unit = when {
        feature.page -> ({ open(pageOf(feature)) })
        locked -> ({
            host.haptics.tick(0.3f)
            host.notice.show(if (!host.ready) connectFirst else readFirst)
        })
        standing == Standing.SENDING || standing == Standing.CHECKING -> ({ host.haptics.tick(0.3f) })
        else -> ({
            host.haptics.toggle()
            if (!toggle(!on)) host.notice.show(connectFirst)
        })
    }
    val (line, warn) = lineOf(feature, standing, waits)
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = tap, onLongClick = { host.haptics.tick(0.5f); details() })
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FeatureTile(feature.icon, lit = standing == Standing.ON, dim = locked && !feature.page)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).alpha(if (locked && !feature.page) 0.55f else 1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(feature.title), style = Visor.type.body, color = colors.textPrimary)
            Text(line, style = Visor.type.label, color = if (warn) colors.signal else colors.textSecondary)
        }
        when {
            standing == Standing.SENDING || standing == Standing.CHECKING -> Spinner(Modifier.padding(start = 12.dp))
            feature.page -> Icon(VisorIcons.Chevron, null, Modifier.padding(start = 8.dp).size(16.dp), tint = colors.textTertiary)
            else -> Box(Modifier.alpha(if (locked) 0.4f else 1f)) { VisorSwitch(on) }
        }
    }
}

/** The feature's icon on a rounded tile, which lights up white while the feature is on. */
@Composable
internal fun FeatureTile(icon: ImageVector, lit: Boolean, dim: Boolean = false, size: Int = 40) {
    val back by animateColorAsState(if (lit) Color.White else Visor.colors.control, tween(240), label = "tile")
    val ink by animateColorAsState(if (lit) Color.Black else Visor.colors.textPrimary, tween(240), label = "ink")
    Box(
        Modifier.size(size.dp).alpha(if (dim) 0.55f else 1f).clip(RoundedCornerShape((size * 0.3f).dp)).background(back),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size((size / 2).dp), tint = ink)
    }
}

/** The line under a feature's name, and whether it is bad news. */
@Composable
private fun lineOf(feature: Feature, standing: Standing, waits: Boolean): Pair<String, Boolean> = when (standing) {
    Standing.UNKNOWN -> stringResource(R.string.features_needs_config) to false
    Standing.OFF -> stringResource(feature.about) to false
    Standing.PARTLY -> stringResource(R.string.features_partly) to false
    Standing.SENDING -> stringResource(R.string.features_sending) to false
    Standing.CHECKING -> stringResource(R.string.features_checking) to false
    Standing.UNCONFIRMED -> stringResource(R.string.features_unconfirmed) to false
    Standing.REFUSED -> stringResource(R.string.features_refused) to true
    Standing.ON -> stringResource(if (waits) R.string.features_restart_line else feature.use) to false
}

/** Where a feature with a page of its own lives. */
private fun pageOf(feature: Feature): NavKey = when (feature) {
    Feature.CONVERSATION_FOCUS -> ConversationFocusKey
    Feature.HEARING_CORRECTION -> HearingCorrectionKey
    else -> error("${feature.id} has no page")
}

@Composable
private fun OutRow(item: OutOfReach) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(item.title), style = Visor.type.body, color = Visor.colors.textSecondary)
        Text(stringResource(item.why), style = Visor.type.label, color = Visor.colors.textTertiary)
    }
}

/** Before anything: the glasses' configuration, which only a bug report carries. */
@Composable
private fun ReadCard(busy: Boolean, read: () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.features_read_title), style = Visor.type.body, color = Visor.colors.textPrimary)
            Text(stringResource(R.string.features_read_body), style = Visor.type.label, color = Visor.colors.textSecondary)
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (busy) {
                    Spinner()
                    Text(stringResource(R.string.features_reading), style = Visor.type.label, color = Visor.colors.textSecondary, modifier = Modifier.padding(start = 10.dp))
                } else {
                    ActionPill(stringResource(R.string.features_read_action), read)
                }
            }
        }
    }
}

/** What waits for the glasses to start again, and the way to say they did. */
@Composable
private fun RestartCard(features: Set<Feature>, done: () -> Unit) {
    val names = features.sortedBy { it.ordinal }.map { stringResource(it.title) }
    Card(Modifier.padding(top = if (features.isEmpty()) 0.dp else 4.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            FeatureTile(VisorIcons.Refresh, lit = true, size = 36)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.features_restart_title), style = Visor.type.body, color = Visor.colors.textPrimary)
                Text(stringResource(R.string.features_restart_body, names.joinToString(", ")), style = Visor.type.label, color = Visor.colors.textSecondary)
                Row(Modifier.padding(top = 10.dp)) { ActionPill(stringResource(R.string.features_restart_done), done) }
            }
        }
    }
}

/** A feature in full: what it does, how to use it, and every switch Visor writes for it, with where each stands. */
@Composable
private fun FeatureSheet(
    feature: Feature,
    standing: Standing,
    on: Boolean,
    config: ConfigState,
    settings: GlassesSettings,
    toggle: (Boolean) -> Boolean,
    onDismiss: () -> Unit,
) {
    val host = LocalHost.current
    val connectFirst = stringResource(R.string.settings_locked)
    VisorSheet(onDismiss) {
        Row(Modifier.padding(horizontal = SIDE).padding(top = 6.dp, bottom = 4.dp)) { FeatureTile(feature.icon, lit = standing == Standing.ON, size = 48) }
        SheetTitle(stringResource(feature.title), stringResource(feature.about))
        Column(Modifier.padding(horizontal = SIDE), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.features_how), style = Visor.type.label, color = Visor.colors.textSecondary)
            Text(stringResource(feature.use), style = Visor.type.body, color = Visor.colors.textPrimary)
            Spacer(Modifier.size(14.dp))
            Text(stringResource(R.string.features_changes), style = Visor.type.label, color = Visor.colors.textSecondary)
            for (gate in feature.gates) KeyLine(gateWords(gate, config, settings))
            if (feature.gates.any { it is Gate.Config && it.needsRestart(config.params) }) {
                Text(stringResource(R.string.features_changes_restart), style = Visor.type.label, color = Visor.colors.textSecondary, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (!feature.page) {
            Row(Modifier.fillMaxWidth().padding(horizontal = SIDE).padding(top = 22.dp)) {
                Spacer(Modifier.weight(1f))
                ActionPill(
                    stringResource(if (on) R.string.features_turn_off else R.string.features_turn_on),
                    {
                        host.haptics.toggle()
                        if (toggle(!on)) onDismiss() else host.notice.show(connectFirst)
                    },
                    enabled = host.ready && standing != Standing.SENDING && standing != Standing.CHECKING,
                )
            }
        }
    }
}

/** Hidden features in a few words, for the row that leads to them. */
@Composable
fun featuresWords(state: FeaturesState): String {
    val on = state.standings.count { it.value == Standing.ON }
    return when {
        state.restart.isNotEmpty() -> stringResource(R.string.features_sum_restart)
        on > 0 -> androidx.compose.ui.res.pluralStringResource(R.plurals.features_sum_on, on, on)
        else -> stringResource(R.string.features_sum)
    }
}

/** One switch in a line: where it lives, what the glasses hold, and what turns the feature on. */
private fun gateWords(gate: Gate, config: ConfigState, settings: GlassesSettings): String = when (gate) {
    is Gate.Config -> "${gate.name}  ${config.reported[gate.name] ?: "–"} → ${gate.on}"
    is Gate.Setting -> "${gate.key}  ${settings.value(gate.key) ?: "–"} → ${gate.on}"
}
