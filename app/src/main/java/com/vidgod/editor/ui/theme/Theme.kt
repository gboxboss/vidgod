package com.vidgod.editor.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object VG {
    val Bg = Color(0xFF000000)
    val Surface = Color(0xFF151517)
    val Surface2 = Color(0xFF222226)
    val Surface3 = Color(0xFF2E2E33)
    val Accent = Color(0xFF00E1FF)
    val Accent2 = Color(0xFFFF2D6F)
    val Text = Color(0xFFF2F2F5)
    val TextDim = Color(0xFF9A9AA3)
    val Divider = Color(0xFF2A2A2E)

    // Timeline item colours
    val TrackMain = Color(0xFF3A3A40)
    val TrackText = Color(0xFFE9A23B)
    val TrackSticker = Color(0xFFE56BA8)
    val TrackAudio = Color(0xFF2FB36E)
    val TrackEffect = Color(0xFF8F5CF0)
    val TrackFilter = Color(0xFF2CA5C9)
    val TrackOverlay = Color(0xFF5468E8)
}

private val scheme = darkColorScheme(
    primary = VG.Accent,
    onPrimary = Color.Black,
    secondary = VG.Accent2,
    onSecondary = Color.White,
    background = VG.Bg,
    onBackground = VG.Text,
    surface = VG.Surface,
    onSurface = VG.Text,
    surfaceVariant = VG.Surface2,
    onSurfaceVariant = VG.TextDim,
    outline = VG.Divider,
)

private val typography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun VidGodTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography) {
        // Screens draw their own dark backgrounds (no Surface), so text and icons need the light
        // content colour explicitly; otherwise they default to black and are invisible.
        CompositionLocalProvider(LocalContentColor provides VG.Text, content = content)
    }
}
