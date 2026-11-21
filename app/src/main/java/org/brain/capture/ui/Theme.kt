package org.brain.capture.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.brain.capture.R

/**
 * The look from the UX concepts canvas: medium greys (never black or white), amber for anything
 * that needs the user, blue for things filed with confidence. One theme, day and night.
 */
object Ink {
    val Ground = Color(0xFF3A3E44)
    val Panel = Color(0xFF44484F)
    val Well = Color(0xFF33363B)
    val Raised = Color(0xFF5A606A)
    val Line = Color(0xFF5C626B)
    val Bar = Color(0xFF6A7078)
    val Text = Color(0xFFEEF0F2)
    val TextStrong = Color(0xFFF5F6F7)
    val TextBody = Color(0xFFE4E7EA)
    val TextSoft = Color(0xFFDDE1E6)
    val TextDim = Color(0xFFC3C8CF)
    val Amber = Color(0xFFF4B860)
    val OnAmber = Color(0xFF2A2118)
    val Blue = Color(0xFF8DBDF5)
    val BlueSoft = Color(0xFF3B4A5C)
    val Error = Color(0xFFFFA48F)
}

/** Brand colours beyond the Material scheme. */
data class Brand(
    val record: Color, val success: Color, val successBg: Color,
    val voiceBg: Color, val voice: Color, val linkBg: Color, val link: Color, val fileBg: Color, val file: Color,
)

private val TheBrand = Brand(
    record = Ink.Amber, success = Ink.Blue, successBg = Ink.BlueSoft,
    voiceBg = Ink.Raised, voice = Ink.Amber, linkBg = Ink.BlueSoft, link = Ink.Blue,
    fileBg = Color(0xFF5A5248), file = Color(0xFFF0C08A),
)

@Composable
fun brand(): Brand = TheBrand

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val Inter = FontFamily(variable(R.font.inter, 400), variable(R.font.inter, 500), variable(R.font.inter, 600))
val Grotesk = FontFamily(variable(R.font.spacegrotesk, 500), variable(R.font.spacegrotesk, 600))
val Mono = FontFamily(variable(R.font.jetbrainsmono, 500))

private val Type = Typography().let { t ->
    fun TextStyle.inter() = copy(fontFamily = Inter)
    fun TextStyle.grotesk() = copy(fontFamily = Grotesk, fontWeight = FontWeight.SemiBold)
    Typography(
        displayLarge = t.displayLarge.grotesk(), displayMedium = t.displayMedium.grotesk(), displaySmall = t.displaySmall.grotesk(),
        headlineLarge = t.headlineLarge.grotesk(), headlineMedium = t.headlineMedium.grotesk(), headlineSmall = t.headlineSmall.grotesk(),
        titleLarge = t.titleLarge.grotesk(), titleMedium = t.titleMedium.inter().copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.inter(),
        bodyLarge = t.bodyLarge.inter(), bodyMedium = t.bodyMedium.inter().copy(fontSize = 15.sp, lineHeight = 23.sp),
        bodySmall = t.bodySmall.inter(),
        labelLarge = t.labelLarge.inter().copy(fontWeight = FontWeight.SemiBold), labelMedium = t.labelMedium.inter(),
        labelSmall = t.labelSmall.inter(),
    )
}

@Composable
fun CaptureTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Ink.Amber, onPrimary = Ink.OnAmber,
        primaryContainer = Ink.Raised, onPrimaryContainer = Ink.TextStrong,
        secondary = Ink.Blue, onSecondary = Ink.Well,
        background = Ink.Ground, onBackground = Ink.Text,
        surface = Ink.Panel, onSurface = Ink.Text,
        surfaceVariant = Ink.Raised, onSurfaceVariant = Ink.TextDim,
        surfaceContainer = Ink.Panel, surfaceContainerHigh = Ink.Panel, surfaceContainerHighest = Ink.Raised,
        surfaceContainerLow = Ink.Well, surfaceContainerLowest = Ink.Well,
        outline = Ink.Line, outlineVariant = Ink.Line,
        error = Ink.Error, onError = Ink.OnAmber, errorContainer = Color(0xFF5C3B35), onErrorContainer = Color(0xFFFFDAD2),
        inverseSurface = Ink.TextStrong, inverseOnSurface = Ink.Well, inversePrimary = Ink.OnAmber,
    )
    MaterialTheme(colorScheme = scheme, typography = Type, content = content)
}
