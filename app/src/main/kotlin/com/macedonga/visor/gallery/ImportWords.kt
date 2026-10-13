package com.macedonga.visor.gallery

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.macedonga.visor.R

/** The choice's name: "While charging", "Always", "Never". */
@Composable
fun AutoImport.words(): String = stringResource(title)

internal val AutoImport.title: Int
    get() = when (this) {
        AutoImport.CHARGING -> R.string.import_charging
        AutoImport.ALWAYS -> R.string.import_always
        AutoImport.OFF -> R.string.import_off
    }

internal val AutoImport.detail: Int
    get() = when (this) {
        AutoImport.CHARGING -> R.string.import_charging_detail
        AutoImport.ALWAYS -> R.string.import_always_detail
        AutoImport.OFF -> R.string.import_off_detail
    }
