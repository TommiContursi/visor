package com.macedonga.visor.features

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import com.macedonga.visor.R
import com.macedonga.visor.core.media.McParam
import com.macedonga.visor.ui.VisorIcons

/** A switch a feature needs, and the value that turns the feature on. */
sealed interface Gate {
    /**
     * A MobileConfig param: it goes out in a table, and the glasses read most of them when they start. [live]: one
     * the glasses keep per session but apply at once all the same, as seen on the glasses, so no restart is asked.
     */
    data class Config(val name: String, val on: Any, val off: Any? = null, val live: Boolean = false) : Gate {
        fun needsRestart(params: Map<String, McParam>) = !live && params[name]?.sessionless == false
    }

    /** A glasses setting: written and read back at once. */
    data class Setting(val key: String, val on: String, val off: String? = null) : Gate
}

/**
 * A feature Meta keeps off on these glasses (for the country, or for testers), what it is, how to use it, and the
 * switches that turn it on. Each was traced in the firmware past its gates to the point where it works.
 */
enum class Feature(
    val id: String,
    @StringRes val title: Int,
    @StringRes val about: Int,
    @StringRes val use: Int,
    val icon: ImageVector,
    val gates: List<Gate>,
    /** Its own page, for a feature with more to set than on and off. */
    val page: Boolean = false,
) {
    CONVERSATION_FOCUS(
        "conversation_focus", R.string.feature_cf, R.string.feature_cf_about, R.string.feature_cf_use, VisorIcons.Ear,
        listOf(Gate.Setting("hearing_boost_nux_completed", "true")),
        page = true,
    ),
    // setupForHCModes refuses "HC is not supported on this device" (INVALID_DEVICE) while the first is off. The second
    // is the version pass Meta's server sets beside it ("update your device"). Both apply at once on the glasses.
    HEARING_CORRECTION(
        "hearing_correction", R.string.feature_hc, R.string.feature_hc_about, R.string.feature_hc_use, VisorIcons.HearingAid,
        listOf(
            Gate.Config("hearing_on_sn.hc_device_supported", true, live = true),
            Gate.Config("hearing_on_sn.hc_device_version_passed", true, live = true),
        ),
        page = true,
    ),
    // The group app's MainActivity finishes unless the first two are on; enable_aurachat starts aurachat through
    // sysprop_map. Without Meta's signed key material the group key is AurachatKeyManager's all-zero DEBUG_KEY.
    GROUP_CONVERSATIONS(
        "group_conversations", R.string.feature_gc, R.string.feature_gc_about, R.string.feature_gc_use, VisorIcons.People,
        listOf(
            Gate.Config("wearables_group_conversations.enabled", true),
            Gate.Config("wearables_group_conversations.min_device_version_passed", true),
            Gate.Config("wearable_connectivity_device_features.enable_aurachat", true),
        ),
    ),
    // The assistant's isLiveAiEnabled takes MobileConfig mmai_live_onframe_enabled or this setting, read on each ask.
    LIVE_AI(
        "live_ai", R.string.feature_live_ai, R.string.feature_live_ai_about, R.string.feature_live_ai_use, VisorIcons.Live,
        listOf(Gate.Setting("live_ai_enabled", "true")),
    ),
    // The glasses' TAR gate (isSgAlarmMCEnabled); Visor's timer service keeps the alarms Meta's app refuses.
    ALARMS(
        "alarms", R.string.feature_alarms, R.string.feature_alarms_about, R.string.feature_alarms_use, VisorIcons.Timer,
        listOf(Gate.Config("assistant_on_sn.sg_alarms_enabled", true)),
    ),
    // SgAudioServerController applies spatial_media_enabled only while xn_spatial_media is on, else logs a no-op.
    SPATIAL_AUDIO(
        "spatial_audio", R.string.feature_spatial, R.string.feature_spatial_about, R.string.feature_spatial_use, VisorIcons.Speaker,
        listOf(Gate.Config("audio_on_xn.xn_spatial_media", true, live = true), Gate.Setting("spatial_media_enabled", "1", off = "0")),
    ),
    FOLLOW_UPS(
        "follow_ups", R.string.feature_follow_ups, R.string.feature_follow_ups_about, R.string.feature_follow_ups_use, VisorIcons.Chat,
        listOf(
            Gate.Config("assistant_on_sn.follow_up_chatty_domains_enabled", true),
            Gate.Config("assistant_on_sn.follow_up_inactive_earcon_enabled", true),
        ),
    ),
    ;

    val needsConfig: Boolean get() = gates.any { it is Gate.Config }
}

/** A feature that cannot work on these glasses with Visor, and why: each was followed past its gates to what stops it. */
enum class OutOfReach(@StringRes val title: Int, @StringRes val why: Int) {
    TUTOR(R.string.out_tutor, R.string.out_tutor_why),
    WAKE_WORD(R.string.out_wake_word, R.string.out_wake_word_why),
    PALM(R.string.out_palm, R.string.out_palm_why),
}
