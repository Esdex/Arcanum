package zip.arcanum.core.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val LocalAmoledMode   = compositionLocalOf { false }
val LocalDarkMode     = compositionLocalOf { false }
val LocalDynamicColor = compositionLocalOf { false }

// Light: saturated colours work against white surfaces; dark containers carry white text (AAA contrast).
private val LightColorScheme = lightColorScheme(
    primary              = ArcanumColors.Primary,       // #259AD2 blue
    onPrimary            = Color.White,
    primaryContainer     = ArcanumColors.PrimaryDark,   // #22697D dark teal container
    onPrimaryContainer   = Color.White,
    secondary            = ArcanumColors.Accent,        // #3AA6AE teal
    onSecondary          = Color.White,
    secondaryContainer   = ArcanumColors.AccentLight,   // #67C0B6 mint-teal container
    onSecondaryContainer = Color(0xFF002020),
    tertiary             = ArcanumColors.AccentDark,    // #3A9AB1 mid-teal
    onTertiary           = Color.White,
)

// Dark: lighter variants for sufficient contrast against dark surfaces.
private val DarkColorScheme = darkColorScheme(
    primary              = ArcanumColors.PrimaryLight,  // #6DD0E0 light cyan
    onPrimary            = Color(0xFF003547),
    primaryContainer     = ArcanumColors.PrimaryDark,   // #22697D dark teal container
    onPrimaryContainer   = ArcanumColors.PrimaryLight,
    secondary            = ArcanumColors.AccentLight,   // #67C0B6 mint-teal
    onSecondary          = Color(0xFF00332F),
    secondaryContainer   = ArcanumColors.Accent,        // #3AA6AE teal container
    onSecondaryContainer = Color(0xFFB2EBEE),
    tertiary             = ArcanumColors.Primary,       // #259AD2 blue
    onTertiary           = Color(0xFF003547),
)

/**
 * Root theme composable with AMOLED + Dynamic Color + Material You support.
 *
 * Exposes [LocalAmoledMode], [LocalDarkMode], [LocalDynamicColor] for child composables.
 */
@Composable
fun AppTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    amoledMode: Boolean = false,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK   -> true
        ThemeMode.LIGHT  -> false
    }

    val context = LocalContext.current
    var colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        isDark -> DarkColorScheme
        else   -> LightColorScheme
    }

    val isAmoled = isDark && amoledMode
    if (isAmoled) {
        colorScheme = colorScheme.copy(
            background     = Color.Black,
            surface        = Color.Black,
            surfaceVariant = Color(0xFF1A1A1A)
        )
    }

    val isDynamic = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    if (isDynamic && !isAmoled) {
        val sv = colorScheme.surfaceVariant
        colorScheme = colorScheme.copy(
            surfaceVariant = Color(sv.red * 0.766f, sv.green * 0.766f, sv.blue * 0.766f)
        )
    }

    CompositionLocalProvider(
        LocalAmoledMode   provides isAmoled,
        LocalDarkMode     provides isDark,
        LocalDynamicColor provides isDynamic
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography  = Typography,
            content     = content
        )
    }
}
