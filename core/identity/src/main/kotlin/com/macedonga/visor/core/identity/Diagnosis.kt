package com.macedonga.visor.core.identity

/** Why the services went quiet. */
enum class Diagnosis {
    /** Trust completed and the canary answered. Nothing is wrong. */
    HEALTHY,

    /** The canary answered and trust did not. The glasses no longer know this key. */
    EVICTED,

    /** Nothing answered. This says nothing about the key, because nothing was asked. */
    LINK_DOWN,

    /** Trust reported success and the canary said nothing. */
    UNKNOWN,
}

/** `diagnose`. */
fun diagnose(trustOk: Boolean, canaryAnswered: Boolean): Diagnosis {
    if (!canaryAnswered) {
        // Nothing below identity is working, so nothing can be concluded about the key.
        return if (!trustOk) Diagnosis.LINK_DOWN else Diagnosis.UNKNOWN
    }
    return if (trustOk) Diagnosis.HEALTHY else Diagnosis.EVICTED
}

/** The bundle's recorded state after a check. */
fun stateAfter(previousState: String, diagnosis: Diagnosis): String = when (diagnosis) {
    Diagnosis.HEALTHY -> BundleState.VERIFIED
    Diagnosis.EVICTED -> BundleState.EVICTED
    else -> previousState
}
