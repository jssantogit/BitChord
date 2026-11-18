package com.music.bitchord.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.music.bitchord.R

// Kept only as a source-compatibility alias while legacy callers are audited.
// Selected states and primary actions use the verified light/night key blue.
@Deprecated("Legacy red accent: use appleMusicKeyColor() for selected and affirmative actions")
val AccentRed = Color(0xFFFA2D48)

internal val DarkColors = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF1C1C1E),
    onPrimaryContainer = Color.White,
    secondary = AppleMusicDarkKey,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF1C1C1E),
    onSecondaryContainer = Color.White,
    tertiary = Color(0xFF8E8E93),
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF1C1C1E),
    onTertiaryContainer = Color.White,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF0D0D0F),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFF8E8E93),
    surfaceTint = Color.Transparent,
    outline = Color(0xFF2C2C2E),
    outlineVariant = Color(0xFF38383A),
)

internal val LightColors = lightColorScheme(
    primary = Color.Black,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF2F2F7),
    onPrimaryContainer = Color.Black,
    secondary = AppleMusicLightKey,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF2F2F7),
    onSecondaryContainer = Color.Black,
    tertiary = Color(0xFF6E6E73),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF2F2F7),
    onTertiaryContainer = Color.Black,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color(0xFFF7F7F9),
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFF2F2F7),
    onSurfaceVariant = Color(0xFF6E6E73),
    surfaceTint = Color.Transparent,
    outline = Color(0xFFE5E5EA),
    outlineVariant = Color(0xFFC6C6C8),
)

/**
 * SF Pro Display, the face Apple Music itself is set in. Only the weights the
 * type scale actually asks for are bundled; Compose synthesises nothing, so a
 * missing weight would silently fall back to the nearest one shipped.
 */
val SFProDisplay = FontFamily(
    Font(R.font.sf_pro_display_regular, FontWeight.W400),
    Font(R.font.sf_pro_display_medium, FontWeight.W500),
    Font(R.font.sf_pro_display_semibold, FontWeight.W600),
    Font(R.font.sf_pro_display_bold, FontWeight.W700),
    Font(R.font.sf_pro_display_heavy, FontWeight.W800),
)

/**
 * Global Android phone scale for the shared native BitChord components.
 *
 * am-descomp (Android 7.0.0/1614, typography-components.md) statically links
 * the library detail title to 28sp/700, a candidate editorial header to
 * 20sp/700, a header link to 17sp/400 and the base miniplayer to 13sp/510
 * plus 12sp/400. These are reference roles, not a measured Compose-wide
 * style map. The remaining roles below are cohesive Bitchord adaptations.
 *
 * This build already bundles SF Pro Display: do not silently replace it with
 * the separately archived, copyright-sensitive SF Pro Android UI reference.
 * No global letter spacing is forced: the REA's tracking calculation is
 * conditional and has not been shown to apply to Compose text.
 */
internal val BitChordTypography = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.W700, fontSize = 34.sp),
    displayMedium = TextStyle(fontWeight = FontWeight.W700, fontSize = 32.sp),
    displaySmall = TextStyle(fontWeight = FontWeight.W700, fontSize = 30.sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.W700, fontSize = 28.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.W700, fontSize = 20.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.W600, fontSize = 20.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.W700, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.W600, fontSize = 17.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.W600, fontSize = 15.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.W400, fontSize = 17.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.W400, fontSize = 15.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.W400, fontSize = 13.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.W600, fontSize = 15.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.W600, fontSize = 13.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.W500, fontSize = 11.sp),
).withFamily(SFProDisplay)

/** Applies [family] to every style in the scale, so nothing is left on Roboto. */
private fun Typography.withFamily(family: FontFamily) = Typography(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
)

@Composable
fun BitChordTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = BitChordTypography,
        content = content,
    )
}

/**
 * Draws the status and navigation bar glyphs dark or light.
 *
 * `enableEdgeToEdge()` decides this from the *system* dark-mode setting, which
 * is the wrong input the moment the in-app theme disagrees with it: Light theme
 * on a phone in dark mode left white icons on a white bar, invisible. The bars
 * have to follow the content the app is actually painting. The player supplies
 * its own stable light-icon value and paints contrast behind it; every other
 * surface follows the theme. Hence a parameter rather than reading it here.
 */
@Composable
fun SystemBarIcons(dark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val window = findWindow(view) ?: return
    SideEffect {
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = dark
            isAppearanceLightNavigationBars = dark
        }
    }
}

