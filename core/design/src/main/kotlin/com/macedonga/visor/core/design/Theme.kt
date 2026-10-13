package com.macedonga.visor.core.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle

/** The one theme. */
@Composable
fun VisorTheme(
    colors: VisorColors = VisorColors(),
    typography: VisorTypography = VisorTypography(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalVisorColors provides colors,
        LocalVisorTypography provides typography,
    ) {
        MaterialTheme(
            colorScheme = colors.toMaterialScheme(),
            typography = typography.toMaterialTypography(),
            content = content,
        )
    }
}

/** Read the tokens as `Visor.colors`, `Visor.type` and so on. */
object Visor {
    val colors: VisorColors
        @Composable @ReadOnlyComposable get() = LocalVisorColors.current

    val type: VisorTypography
        @Composable @ReadOnlyComposable get() = LocalVisorTypography.current
}

/** Feeds Visor's values to the M3 components that read a colour scheme: white is the action colour. */
private fun VisorColors.toMaterialScheme() = darkColorScheme(
    primary = textPrimary,
    onPrimary = canvas,
    secondary = control,
    onSecondary = textPrimary,
    tertiary = lightNeutral,
    onTertiary = canvas,
    background = canvas,
    onBackground = textPrimary,
    surface = surface,
    onSurface = textPrimary,
    surfaceVariant = raised,
    onSurfaceVariant = textSecondary,
    surfaceContainer = surface,
    surfaceContainerHigh = raised,
    surfaceContainerHighest = control,
    outline = textTertiary,
    outlineVariant = hairline,
    error = signal,
    onError = canvas,
)

private fun VisorTypography.toMaterialTypography(): Typography {
    val base = Typography()
    return base.copy(
        displayLarge = display.mergedOnto(base.displayLarge),
        displayMedium = display.mergedOnto(base.displayMedium),
        displaySmall = title.mergedOnto(base.displaySmall),
        headlineLarge = title.mergedOnto(base.headlineLarge),
        headlineMedium = title.mergedOnto(base.headlineMedium),
        headlineSmall = heading.mergedOnto(base.headlineSmall),
        titleLarge = title.mergedOnto(base.titleLarge),
        titleMedium = heading.mergedOnto(base.titleMedium),
        titleSmall = heading.mergedOnto(base.titleSmall),
        bodyLarge = body.mergedOnto(base.bodyLarge),
        bodyMedium = body.mergedOnto(base.bodyMedium),
        bodySmall = label.mergedOnto(base.bodySmall),
        labelLarge = label.mergedOnto(base.labelLarge),
        labelMedium = label.mergedOnto(base.labelMedium),
        labelSmall = label.mergedOnto(base.labelSmall),
    )
}

/** Keeps the M3 role's own size and spacing where Visor does not override it. */
private fun TextStyle.mergedOnto(role: TextStyle): TextStyle = role.merge(this)
