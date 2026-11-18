package com.music.bitchord.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.theme.rememberArtworkPalette
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/**
 * One neutral material language for the Library filter reference, selection,
 * ratings and held-action menus. The centered rating Dialog cannot sample
 * the host Haze: it must use the exact shared popup base (not a fake blurred
 * texture). Artwork may color the PAGE, never contextual action plates.
 */
internal fun nativeContextPlateColor(dark: Boolean): Color =
    nativeCompactPlateColor(isDark = dark, artworkElevation = null)

internal enum class NativeActionCardTreatment { FROSTED, SOLID, NEUTRAL_FROSTED }

/** The centered rating modal uses the exact neutral popup surface, without artwork. */
internal fun neutralRatingPlateUsesArtwork(treatment: NativeActionCardTreatment): Boolean =
    treatment == NativeActionCardTreatment.FROSTED

/** The rating modal is rounder; existing library/held cards stay unchanged. */
internal fun nativeContextPlateCornerRadiusDp(treatment: NativeActionCardTreatment): Int =
    if (treatment == NativeActionCardTreatment.NEUTRAL_FROSTED) 30 else 18

/** Rating has no outline; preserve the existing subtle borders on other card types. */
internal fun nativeContextPlateBorder(
    treatment: NativeActionCardTreatment,
    dark: Boolean,
): BorderStroke? {
    if (treatment == NativeActionCardTreatment.NEUTRAL_FROSTED) return null
    return BorderStroke(
        0.5.dp,
        (if (dark) Color.White else Color.Black).copy(
            alpha = if (dark) 0.13f else 0.08f,
        ),
    )
}

internal fun expandableActionCardUsesHaze(
    treatment: NativeActionCardTreatment,
    hazeAvailable: Boolean,
    reduceBlur: Boolean,
): Boolean = treatment == NativeActionCardTreatment.FROSTED && hazeAvailable && !reduceBlur

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
internal fun NativeContextActionPlate(
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    /** Already verified album art in a standalone rating Dialog. */
    artworkUrl: String? = null,
    treatment: NativeActionCardTreatment = NativeActionCardTreatment.FROSTED,
    content: @Composable () -> Unit,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val reduceBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val shape = RoundedCornerShape(nativeContextPlateCornerRadiusDp(treatment).dp)
    val base = nativeContextPlateColor(dark)
    val palette = rememberArtworkPalette(artworkUrl)
    val useHaze = expandableActionCardUsesHaze(
        treatment = treatment, hazeAvailable = hazeState != null, reduceBlur = reduceBlur,
    )
    val useArtwork = neutralRatingPlateUsesArtwork(treatment) &&
        hazeState == null && artworkUrl != null && !reduceBlur
    Surface(
        modifier = modifier.shadow(12.dp, shape, clip = false),
        shape = shape,
        color = Color.Transparent,
        contentColor = if (dark) Color.White else Color.Black,
        border = nativeContextPlateBorder(treatment, dark),
    ) {
        Box(
            Modifier.clip(shape)
                // Never rely on an asynchronous Haze texture to make text legible.
                .background(base)
                .then(
                    if (useHaze) Modifier.optimizedHazeEffect(
                        state = requireNotNull(hazeState),
                        style = HazeMaterials.regular(base.copy(alpha = 0.58f)),
                    ) else Modifier,
                ),
        ) {
            if (useArtwork) {
                ArtworkBackdrop(
                    palette = palette,
                    imageUrl = requireNotNull(artworkUrl),
                    modifier = Modifier.matchParentSize(),
                    washFraction = 1f,
                )
                // The underlying art is still diffused, but never vibrant
                // enough to make white actions visually disappear.
                Box(Modifier.matchParentSize().background(base.copy(alpha = 0.78f)))
            }
            content()
        }
    }
}
