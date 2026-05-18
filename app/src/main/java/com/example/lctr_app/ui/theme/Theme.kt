package com.example.lctr_app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = SystemGray,
    secondary = SystemGrayDark,
    tertiary = SystemGray,
    background = SystemSurface,
    surface = Color.White,
)

@Composable
fun Lctr_appTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        content = content,
    )
}
