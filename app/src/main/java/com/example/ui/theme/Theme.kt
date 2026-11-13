package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val YektaDarkColorScheme = darkColorScheme(
    primary = CyanPulse,
    onPrimary = ObsidianVoid,
    primaryContainer = CyanDeep,
    onPrimaryContainer = SlateInk,
    secondary = EmeraldVerified,
    onSecondary = ObsidianVoid,
    secondaryContainer = EmeraldContainer,
    onSecondaryContainer = SlateInk,
    tertiary = VioletAccent,
    onTertiary = ObsidianVoid,
    background = ObsidianVoid,
    onBackground = SlateInk,
    surface = ObsidianSurface,
    onSurface = SlateInk,
    surfaceVariant = ObsidianSurfaceElevated,
    onSurfaceVariant = SlateMuted,
    outline = ObsidianOutline,
    error = CrimsonBlock,
    onError = Color.White
)

private val YektaLightColorScheme = lightColorScheme(
    primary = DeepCyanPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFF4FA),
    onPrimaryContainer = Color(0xFF002026),
    secondary = EmeraldVerified,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1FAE5),
    onSecondaryContainer = Color(0xFF064E3B),
    tertiary = VioletAccent,
    onTertiary = Color.White,
    background = PearlCanvas,
    onBackground = DeepObsidianInk,
    surface = PearlSurface,
    onSurface = DeepObsidianInk,
    surfaceVariant = PearlSurfaceVariant,
    onSurfaceVariant = SlateBodyLight,
    outline = PearlOutline,
    error = CrimsonBlock,
    onError = Color.White
)

val YektaShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun YektaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> YektaDarkColorScheme
        else -> YektaLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = YektaShapes,
        content = content
    )
}
