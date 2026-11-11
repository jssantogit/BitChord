package com.music.bitchord.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.player.AppleQueueVectors

/** Display-only choice: never changes the first-party rating or its source. */
enum class CompactRatingStyle { COLOR, NUMERIC }
enum class CompactRatingGeometry { CIRCULAR, STANDALONE }
val LocalCompactRatingStyle = compositionLocalOf { CompactRatingStyle.COLOR }

/** Nine valid steps; no score is ever inferred from a favorite or remote flag. */
fun normalizedCompactRating(value: Double?): Double? =
    value?.takeIf { it.isFinite() && it in 1.0..5.0 && it * 2.0 == (it * 2.0).toInt().toDouble() }

private val scoreColors = listOf(
    Color(0xFFD96979), Color(0xFFF09852), Color(0xFFE9C64B),
    Color(0xFF40CBAA), Color(0xFF0091FF),
)

fun compactRatingColorStops(value: Double): List<Pair<Float, Color>> {
    val score = normalizedCompactRating(value) ?: return emptyList()
    val lower = score.toInt()
    val first = scoreColors[lower - 1]
    if (score == lower.toDouble()) return listOf(0f to first, 1f to first)
    val second = scoreColors[lower]
    // BitChord V2, diagonal sRGB transition from 40 to 60 percent.
    return listOf(0f to first, .4f to first, .6f to second, 1f to second)
}

fun compactRatingAccessibility(value: Double?): String {
    val score = normalizedCompactRating(value) ?: return "Sem avaliação"
    val text = if (score == score.toInt().toDouble()) score.toInt().toString()
        else score.toString().replace('.', ',')
    return "Avaliação: " + text + " de 5 estrelas"
}

/** Numeric ink is legible against the actual light/dark theme, not hardcoded white. */
fun compactRatingNumberTint(dark: Boolean): Color = if (dark) Color.White else Color.Black

/**
 * Glyph outlines come directly from the user's V3 SVG handoff. Both geometries
 * use these SAME 28x28 paths, preventing the old scale mismatch. Nothing is
 * rasterized and no font file is packaged.
 */
internal fun compactRatingDigitPath(value: Double): String = when (value) {
    1.0 -> "M12.082,16.160L13.394,16.160L13.394,12.023L12.048,12.332L12.048,11.209L13.385,10.900L14.799,10.900L14.799,16.160L16.112,16.160L16.112,17.300L12.082,17.300L12.082,16.160Z"
    1.5 -> "M10.380,13.990L11.576,13.990L11.576,10.597L10.349,10.850L10.349,9.928L11.568,9.675L12.855,9.675L12.855,13.990L14.051,13.990L14.051,14.925L10.380,14.925L10.380,13.990ZM14.215,13.725L17.516,13.725L17.516,14.702L15.275,14.702L15.275,15.499Q15.426,15.457 15.579,15.435Q15.733,15.411 15.898,15.411Q16.840,15.411 17.364,15.883Q17.888,16.354 17.888,17.195Q17.888,18.029 17.317,18.503Q16.747,18.975 15.733,18.975Q15.294,18.975 14.865,18.890Q14.436,18.805 14.012,18.633L14.012,17.588Q14.432,17.829 14.810,17.951Q15.187,18.071 15.523,18.071Q16.005,18.071 16.282,17.835Q16.560,17.599 16.560,17.195Q16.560,16.788 16.282,16.554Q16.005,16.319 15.523,16.319Q15.236,16.319 14.911,16.394Q14.588,16.468 14.215,16.622L14.215,13.725Z"
    2.0 -> "M13.646,16.109L16.135,16.109L16.135,17.300L12.025,17.300L12.025,16.109L14.089,14.082Q14.367,13.805 14.499,13.539Q14.632,13.274 14.632,12.988Q14.632,12.546 14.364,12.277Q14.098,12.008 13.656,12.008Q13.315,12.008 12.908,12.169Q12.501,12.331 12.039,12.652L12.039,11.271Q12.531,11.090 13.013,10.996Q13.495,10.900 13.959,10.900Q14.973,10.900 15.535,11.397Q16.097,11.893 16.097,12.782Q16.097,13.296 15.859,13.740Q15.621,14.184 14.855,14.930L13.646,16.109Z"
    2.5 -> "M11.805,13.948L14.074,13.948L14.074,14.925L10.326,14.925L10.326,13.948L12.208,12.286Q12.461,12.058 12.582,11.840Q12.702,11.622 12.702,11.388Q12.702,11.026 12.459,10.805Q12.216,10.584 11.811,10.584Q11.501,10.584 11.131,10.716Q10.761,10.849 10.340,11.112L10.340,9.979Q10.789,9.831 11.228,9.753Q11.667,9.675 12.088,9.675Q13.014,9.675 13.527,10.083Q14.040,10.490 14.040,11.219Q14.040,11.640 13.822,12.005Q13.604,12.369 12.907,12.981L11.805,13.948ZM14.215,13.725L17.516,13.725L17.516,14.702L15.275,14.702L15.275,15.499Q15.426,15.457 15.579,15.435Q15.733,15.411 15.898,15.411Q16.840,15.411 17.364,15.883Q17.888,16.354 17.888,17.195Q17.888,18.029 17.317,18.503Q16.747,18.975 15.733,18.975Q15.294,18.975 14.865,18.890Q14.436,18.805 14.012,18.633L14.012,17.588Q14.432,17.829 14.810,17.951Q15.187,18.071 15.523,18.071Q16.005,18.071 16.282,17.835Q16.560,17.599 16.560,17.195Q16.560,16.788 16.282,16.554Q16.005,16.319 15.523,16.319Q15.236,16.319 14.911,16.394Q14.588,16.468 14.215,16.622L14.215,13.725Z"
    3.0 -> "M15.028,13.853Q15.586,14.015 15.879,14.414Q16.173,14.812 16.173,15.428Q16.173,16.345 15.541,16.823Q14.909,17.300 13.694,17.300Q13.268,17.300 12.839,17.223Q12.409,17.147 11.987,16.993L11.987,15.766Q12.389,15.990 12.786,16.104Q13.182,16.217 13.566,16.217Q14.136,16.217 14.437,15.999Q14.740,15.780 14.740,15.370Q14.740,14.948 14.429,14.731Q14.120,14.514 13.513,14.514L12.942,14.514L12.942,13.490L13.546,13.490Q14.083,13.490 14.347,13.302Q14.611,13.114 14.611,12.730Q14.611,12.375 14.355,12.181Q14.099,11.987 13.628,11.987Q13.281,11.987 12.930,12.074Q12.578,12.160 12.228,12.329L12.228,11.164Q12.654,11.032 13.069,10.966Q13.484,10.900 13.884,10.900Q14.967,10.900 15.504,11.295Q16.041,11.689 16.041,12.482Q16.041,13.023 15.784,13.368Q15.529,13.713 15.028,13.853Z"
    3.5 -> "M13.062,12.098Q13.574,12.230 13.840,12.558Q14.107,12.884 14.107,13.389Q14.107,14.142 13.530,14.534Q12.954,14.925 11.850,14.925Q11.459,14.925 11.068,14.862Q10.676,14.799 10.293,14.673L10.293,13.667Q10.660,13.850 11.020,13.944Q11.381,14.037 11.730,14.037Q12.249,14.037 12.525,13.858Q12.802,13.678 12.802,13.342Q12.802,12.996 12.519,12.818Q12.236,12.640 11.683,12.640L11.161,12.640L11.161,11.800L11.710,11.800Q12.202,11.800 12.442,11.646Q12.683,11.492 12.683,11.176Q12.683,10.885 12.448,10.726Q12.215,10.566 11.788,10.566Q11.473,10.566 11.151,10.638Q10.829,10.709 10.510,10.847L10.510,9.892Q10.896,9.783 11.276,9.729Q11.656,9.675 12.022,9.675Q13.008,9.675 13.497,9.999Q13.987,10.322 13.987,10.973Q13.987,11.417 13.753,11.700Q13.520,11.983 13.062,12.098ZM14.215,13.725L17.516,13.725L17.516,14.702L15.275,14.702L15.275,15.499Q15.426,15.457 15.579,15.435Q15.733,15.411 15.898,15.411Q16.840,15.411 17.364,15.883Q17.888,16.354 17.888,17.195Q17.888,18.029 17.317,18.503Q16.747,18.975 15.733,18.975Q15.294,18.975 14.865,18.890Q14.436,18.805 14.012,18.633L14.012,17.588Q14.432,17.829 14.810,17.951Q15.187,18.071 15.523,18.071Q16.005,18.071 16.282,17.835Q16.560,17.599 16.560,17.195Q16.560,16.788 16.282,16.554Q16.005,16.319 15.523,16.319Q15.236,16.319 14.911,16.394Q14.588,16.468 14.215,16.622L14.215,13.725Z"
    4.0 -> "M14.245,12.259L12.616,14.938L14.245,14.938L14.245,12.259ZM13.997,10.900L15.647,10.900L15.647,14.938L16.470,14.938L16.470,16.134L15.647,16.134L15.647,17.300L14.245,17.300L14.245,16.134L11.690,16.134L11.690,14.720L13.997,10.900Z"
    4.5 -> "M12.349,10.790L10.866,12.987L12.349,12.987L12.349,10.790ZM12.124,9.675L13.630,9.675L13.630,12.987L14.378,12.987L14.378,13.969L13.630,13.969L13.630,14.925L12.349,14.925L12.349,13.969L10.022,13.969L10.022,12.809L12.124,9.675ZM14.215,13.725L17.516,13.725L17.516,14.702L15.275,14.702L15.275,15.499Q15.426,15.457 15.579,15.435Q15.733,15.411 15.898,15.411Q16.840,15.411 17.364,15.883Q17.888,16.354 17.888,17.195Q17.888,18.029 17.317,18.503Q16.747,18.975 15.733,18.975Q15.294,18.975 14.865,18.890Q14.436,18.805 14.012,18.633L14.012,17.588Q14.432,17.829 14.810,17.951Q15.187,18.071 15.523,18.071Q16.005,18.071 16.282,17.835Q16.560,17.599 16.560,17.195Q16.560,16.788 16.282,16.554Q16.005,16.319 15.523,16.319Q15.236,16.319 14.911,16.394Q14.588,16.468 14.215,16.622L14.215,13.725Z"
    5.0 -> "M12.175,10.900L15.799,10.900L15.799,12.091L13.340,12.091L13.340,13.062Q13.504,13.011 13.672,12.984Q13.841,12.956 14.021,12.956Q15.055,12.956 15.631,13.530Q16.207,14.105 16.207,15.130Q16.207,16.147 15.580,16.724Q14.955,17.300 13.841,17.300Q13.362,17.300 12.891,17.196Q12.420,17.093 11.953,16.883L11.953,15.609Q12.416,15.903 12.829,16.051Q13.243,16.198 13.609,16.198Q14.139,16.198 14.443,15.910Q14.749,15.622 14.749,15.130Q14.749,14.633 14.443,14.348Q14.139,14.062 13.609,14.062Q13.298,14.062 12.940,14.153Q12.584,14.243 12.175,14.432L12.175,10.900Z"
    else -> ""
}

@Composable
fun CompactRatingIndicator(
    value: Double?,
    geometry: CompactRatingGeometry,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    showUnrated: Boolean = false,
    neutralColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val score = normalizedCompactRating(value)
    if (score == null && !showUnrated) return
    val style = LocalCompactRatingStyle.current
    val foreground = compactRatingNumberTint(
        MaterialTheme.colorScheme.background.luminance() < .5f,
    )
    val circular = geometry == CompactRatingGeometry.CIRCULAR
    val outline = if (circular) AppleQueueVectors.Favorite else AppleQueueVectors.RatingStarOutline
    val filled = if (circular) AppleQueueVectors.Favorited else AppleQueueVectors.RatingStarFilled
    Box(
        modifier = modifier.size(size)
            .testTag("compact-rating-indicator")
            .semantics { contentDescription = compactRatingAccessibility(score) },
        contentAlignment = Alignment.Center,
    ) {
        if (score == null) {
            Icon(outline, contentDescription = null, tint = neutralColor, modifier = Modifier.fillMaxSize())
        } else if (style == CompactRatingStyle.COLOR) {
            val stops = compactRatingColorStops(score)
            val brush: Brush = if (stops.first().second == stops.last().second)
                SolidColor(stops.first().second)
            else Brush.linearGradient(*stops.toTypedArray())
            Icon(
                imageVector = filled,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.fillMaxSize()
                    .testTag("compact-rating-color")
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(brush = brush, blendMode = BlendMode.SrcIn)
                    },
            )
        } else {
            val numbers = remember(score) {
                PathParser().parsePathString(compactRatingDigitPath(score)).toPath().apply {
                    fillType = PathFillType.EvenOdd
                }
            }
            Icon(
                imageVector = filled,
                contentDescription = null,
                tint = foreground,
                modifier = if (circular) Modifier.fillMaxSize()
                    .testTag("compact-rating-numeric")
                else Modifier.fillMaxSize()
                    .testTag("compact-rating-numeric")
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        withTransform({
                            scale(this.size.width / 28f, this.size.height / 28f, pivot = androidx.compose.ui.geometry.Offset.Zero)
                        }) {
                            drawPath(numbers, Color.Black, blendMode = BlendMode.Clear)
                        }
                    },
            )
            if (circular) {
                Canvas(Modifier.fillMaxSize()) {
                    withTransform({
                        scale(this.size.width / 28f, this.size.height / 28f, pivot = androidx.compose.ui.geometry.Offset.Zero)
                    }) {
                        clipPath(AppleQueueVectors.RatingStarContour) {
                            drawPath(numbers, foreground)
                        }
                    }
                }
            }
        }
    }
}
