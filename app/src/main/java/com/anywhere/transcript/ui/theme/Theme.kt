package com.anywhere.transcript.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.anywhere.transcript.data.AppSettings

private val Primary = Color(0xFF564FC9)
private val PrimaryDark = Color(0xFFC3C0FF)
private val Secondary = Color(0xFF7D67E8)
private val Tertiary = Color(0xFF00B3A0)

private val LightColors = lightColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E0FF),
    onPrimaryContainer = Color(0xFF170068),
    secondary = Secondary,
    secondaryContainer = Color(0xFFE6DEFF),
    tertiary = Tertiary,
    tertiaryContainer = Color(0xFF9CF2E4),
    background = Color(0xFFFDF7FF),
    surface = Color(0xFFFDF7FF),
    surfaceVariant = Color(0xFFE5E1EC),
)

private val DarkColors = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = Color(0xFF241A78),
    primaryContainer = Color(0xFF3D33A0),
    onPrimaryContainer = Color(0xFFE4E0FF),
    secondary = Color(0xFFC9BFFF),
    secondaryContainer = Color(0xFF4A3BA8),
    tertiary = Color(0xFF80D5C8),
    tertiaryContainer = Color(0xFF005049),
    background = Color(0xFF131318),
    surface = Color(0xFF131318),
    surfaceVariant = Color(0xFF47464F),
)

@Composable
fun AppTheme(
    settings: AppSettings,
    content: @Composable () -> Unit,
) {
    val dark = when (settings.themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val colorScheme = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
