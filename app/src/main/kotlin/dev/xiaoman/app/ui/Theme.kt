package dev.xiaoman.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xiaoman.app.settings.AppearanceSettings
import dev.xiaoman.app.settings.ThemeMode
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/*
 * Xiaoman palette. Every slot the Material 3 components read is filled in, so no stock
 * purple/rose default leaks through on chips, switches, cards or the navigation bar.
 */
private val Green = Color(0xFF1E6B52)

private val LightScheme = lightColorScheme(
    primary = Green,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDAEFE2),
    onPrimaryContainer = Color(0xFF0C3626),
    inversePrimary = Color(0xFF8BD6B5),

    secondary = Color(0xFF4F6B5B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE3EFE6),
    onSecondaryContainer = Color(0xFF1D3A2A),

    tertiary = Color(0xFF5E6B45),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE8EED4),
    onTertiaryContainer = Color(0xFF2A3313),

    background = Color(0xFFF7F9F6),
    onBackground = Color(0xFF202C25),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF202C25),
    surfaceVariant = Color(0xFFEAF0E8),
    onSurfaceVariant = Color(0xFF5B6B60),
    surfaceTint = Green,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F7F2),
    surfaceContainer = Color(0xFFEDF3EC),
    surfaceContainerHigh = Color(0xFFE7EEE6),
    surfaceContainerHighest = Color(0xFFE1E9E0),

    outline = Color(0xFFB6C3B9),
    outlineVariant = Color(0xFFDCE5DC),

    inverseSurface = Color(0xFF2E332F),
    inverseOnSurface = Color(0xFFF0F4EF),

    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8BD6B5),
    onPrimary = Color(0xFF0D3B27),
    primaryContainer = Color(0xFF204D3C),
    onPrimaryContainer = Color(0xFFC6EEDC),
    inversePrimary = Green,

    secondary = Color(0xFFA9C9B6),
    onSecondary = Color(0xFF12301F),
    secondaryContainer = Color(0xFF2A3D31),
    onSecondaryContainer = Color(0xFFCFE6D9),

    tertiary = Color(0xFFC3D2A3),
    onTertiary = Color(0xFF2A3313),
    tertiaryContainer = Color(0xFF3A4530),
    onTertiaryContainer = Color(0xFFDFE9C7),

    background = Color(0xFF121815),
    onBackground = Color(0xFFE2E9E3),
    surface = Color(0xFF161D19),
    onSurface = Color(0xFFE2E9E3),
    surfaceVariant = Color(0xFF2A342D),
    onSurfaceVariant = Color(0xFFB4C2B8),
    surfaceTint = Color(0xFF8BD6B5),
    surfaceContainerLowest = Color(0xFF0E1310),
    surfaceContainerLow = Color(0xFF1B231F),
    surfaceContainer = Color(0xFF1F2823),
    surfaceContainerHigh = Color(0xFF252F28),
    surfaceContainerHighest = Color(0xFF2C362F),

    outline = Color(0xFF7F8C84),
    outlineVariant = Color(0xFF35463C),

    inverseSurface = Color(0xFFE2E9E3),
    inverseOnSurface = Color(0xFF2E332F),

    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

/** Stable Xiaoman colors; readable light/dark surfaces independent of the phone wallpaper. */
@Composable
fun XiaomanTheme(
    appearance: AppearanceSettings,
    onDarkModeChanged: (Boolean) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val theme by appearance.theme.collectAsStateWithLifecycle()
    val language by appearance.language.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val localized = remember(context, configuration, language) {
        appearance.localizedContext(context, language)
    }
    val dark = when (theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    androidx.compose.runtime.SideEffect { onDarkModeChanged(dark) }
    val scheme = if (dark) DarkScheme else LightScheme
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
            bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
            titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
            titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
            labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp),
        ),
        content = {
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides localized.resources.configuration,
                content = content,
            )
        },
    )
}
