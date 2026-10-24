package pl.wystaw.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF15191E)
val InkSoft = Color(0xFF2A3038)
val Lime = Color(0xFFC8F169)
val Ground = Color(0xFFF6F5F1)
val Muted = Color(0xFF5B616B)
val Line = Color(0xFFDEDBD2)
val AmberBg = Color(0xFFFFF4D6)
val AmberText = Color(0xFF5C3D00)
val GreenBg = Color(0xFFDDF3EC)
val GreenText = Color(0xFF0B6B58)
val ErrorRed = Color(0xFFB3261E)

@Composable
fun WystawTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Ink,
            onPrimary = Color.White,
            secondary = Lime,
            onSecondary = Ink,
            background = Ground,
            onBackground = Ink,
            surface = Color.White,
            onSurface = Ink,
            surfaceVariant = Color(0xFFEDEBE4),
            onSurfaceVariant = Muted,
            outline = Line,
            error = ErrorRed,
        ),
        content = content,
    )
}
