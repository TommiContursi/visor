package com.macedonga.visor.stage

import kotlinx.coroutines.flow.MutableStateFlow

/** Overrides the debug receiver sets to look at every light without the glasses: null follows the glasses. */
object StageDebug {
    val mood = MutableStateFlow<Mood?>(null)
    val variant = MutableStateFlow<String?>(null)
    /** The setup held on a page, and its pairing story held at a second. */
    val onboardingPage = MutableStateFlow<String?>(null)
    val demoAt = MutableStateFlow<Float?>(null)
}
