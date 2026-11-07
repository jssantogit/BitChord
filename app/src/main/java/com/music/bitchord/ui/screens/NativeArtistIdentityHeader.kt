package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.transformations
import com.music.bitchord.R
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.canvas.AppleArtistArt
import com.music.bitchord.data.canvas.ArtistSignature
import com.music.bitchord.data.canvas.SignatureInkTransformation
import com.music.bitchord.ui.theme.ArtworkPalette
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Original native header and signatures, independent from DetailPage. */
private val HEADER_DROP = 44.dp
private val ARTIST_HEADER_LIFT = 64.dp
private const val ARTIST_LOGO_WIDTH = 0.82f
private val ABOUT_LOGO_HEIGHT = 56.dp
private const val SIGNATURE_SCALE = 0.7f
private const val ARTIST_LOGO_MAX_HEIGHT = 0.30f
private const val LOGO_RETRIES = 2
private const val LOGO_RETRY_DELAY_MS = 800L
private const val SVG_DECODE_WIDTH = 1600

@Composable
internal fun ArtistHeader(
    artistName: String,
    palette: ArtworkPalette,
    artHeight: Dp,
    appleArt: AppleArtistArt?,
    signature: ArtistSignature?,
) {
    Box(Modifier.fillMaxWidth()) {
        Spacer(Modifier.fillMaxWidth().height(artHeight + HEADER_DROP - ARTIST_HEADER_LIFT))
        val logoUrl = appleArt?.logoUrl
        // Secondary provider: drawn only when Apple has no title logo.
        val signatureUrl = signature?.imageUrl.takeIf { logoUrl == null }
        val imageUrl = logoUrl ?: signatureUrl
        // Signature vectors are dark ink on transparency — over a dark artist
        // photo they vanish, so they are tinted white like the web UI's
        // inverted plate. Scanned photos (jpeg/png) keep their own pixels:
        // tinting an opaque light scan would flatten it into a white blob.
        val tint = if (logoUrl == null && signature?.format == "svg") {
            ColorFilter.tint(Color.White)
        } else {
            null
        }
        // Whether the logo has actually been drawn. Until it has — a first fetch
        // of an Apple logo is a few hundred KB the CDN may still be resizing — the
        // name stands in for it, so the header is never left with nothing in it.
        var logoShown by remember(imageUrl) { mutableStateOf(false) }
        if (imageUrl != null) {
            val context = LocalPlatformContext.current
            var attempt by remember(imageUrl) { mutableIntStateOf(0) }
            var failed by remember(imageUrl) { mutableStateOf(false) }
            val isSignatureSvg = logoUrl == null && signature?.format == "svg"
            // Scanned paper (jpeg/png) goes through the ink transformation
            // (white paper to transparency, strokes to white); vectors are
            // tinted at draw time instead.
            val isSignatureScan = logoUrl == null && signature != null && !isSignatureSvg
            val aspect = (if (logoUrl != null) appleArt?.logoAspect else signature?.aspect)
                ?.coerceIn(0.4f, 6f) ?: 1f
            // Remembered, and at the size the URL already names: nothing about the
            // load waits on layout to decide how big to decode, and a recomposition
            // can't hand Coil a new request that cancels the one in flight.
            // Vectors decode small (Charlie Puth's is 213px) and would upscale
            // ~3x into a blur, so SVGs render large; rasters keep ORIGINAL
            // since decoding past their pixels only spends memory.
            val request = remember(imageUrl, attempt) {
                ImageRequest.Builder(context).data(imageUrl).apply {
                    if (isSignatureSvg) {
                        size(SVG_DECODE_WIDTH, (SVG_DECODE_WIDTH / aspect).roundToInt())
                    } else {
                        size(coil3.size.Size.ORIGINAL)
                    }
                    if (isSignatureScan) transformations(SignatureInkTransformation)
                }.build()
            }
            // A dropped fetch gets two more tries before the name is left in place.
            LaunchedEffect(failed) {
                if (failed && attempt < LOGO_RETRIES) {
                    delay(LOGO_RETRY_DELAY_MS)
                    failed = false
                    attempt++
                }
            }
            // Apple's own title logo stands in for the name, laid over its photo.
            // Capped in height as well as width: a logo set on two or three
            // lines is nearly square, and by width alone it would swallow the
            // photograph it is meant to sit on. A signature is a panoramic ink
            // strip and reads best smaller than a title logo.
            val isSignature = logoUrl == null && signature != null
            val sizeFactor = if (isSignature) SIGNATURE_SCALE else 1f
            val maxLogoHeight = artHeight * ARTIST_LOGO_MAX_HEIGHT * sizeFactor
            Column(
                modifier = Modifier.align(Alignment.BottomCenter),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
            AsyncImage(
                model = request,
                contentDescription = artistName,
                contentScale = ContentScale.Fit,
                colorFilter = tint,
                onState = { state ->
                    when (state) {
                        is AsyncImagePainter.State.Success -> logoShown = true
                        is AsyncImagePainter.State.Error -> {
                            val cause = (state as? AsyncImagePainter.State.Error)
                                ?.result?.throwable?.let { "${it.javaClass.simpleName}: ${it.message}" }
                            Log.d("ArtistHeader", "image load failed: $imageUrl attempt $attempt cause=$cause")
                            failed = true
                        }
                        else -> Unit
                    }
                },
                modifier = Modifier
                    .padding(top = 14.dp, bottom = if (isSignature) 6.dp else 20.dp)
                    // Sized from the incoming width in layout rather than through a
                    // BoxWithConstraints, which would subcompose the image.
                    .layout { measurable, constraints ->
                        val widest = constraints.maxWidth * ARTIST_LOGO_WIDTH * sizeFactor
                        val height = minOf(widest / aspect, maxLogoHeight.toPx()).roundToInt()
                        val width = (height * aspect).roundToInt()
                        val placeable = measurable.measure(Constraints.fixed(width, height))
                        layout(width, height) { placeable.place(0, 0) }
                    },
            )
            if (isSignature && logoShown) {
                SignedByCaption(
                    name = artistName,
                    palette = palette,
                    modifier = Modifier.padding(horizontal = HEADER_GUTTER).padding(bottom = 20.dp),
                )
            }
            }
        }
        if (!logoShown) Text(
            text = artistName,
            style = MaterialTheme.typography.displayLarge,
            color = palette.onBackground,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // Bottom is half the top inset — the stats pills (or, absent
                // those, the action row) sit closer under the name than the
                // name sits under the artwork.
                .padding(start = HEADER_GUTTER, end = HEADER_GUTTER, top = 14.dp, bottom = 7.dp),
        )
    }
}

@Composable
private fun SignedByCaption(
    name: String,
    palette: ArtworkPalette,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Center,
) {
    val signed = stringResource(R.string.signed_by, name)
    val nameAt = signed.indexOf(name)
    Text(
        text = buildAnnotatedString {
            append(signed)
            if (nameAt >= 0) {
                addStyle(SpanStyle(fontWeight = FontWeight.Bold), nameAt, nameAt + name.length)
            }
        },
        style = MaterialTheme.typography.bodyMedium,
        color = palette.onBackground.copy(alpha = 0.8f),
        textAlign = textAlign,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * The handwritten signature with its signed-by line, closing the About section of an artist whose
 * header already carries Apple's title logo.
 */
@Composable
internal fun AboutSignature(
    signature: ArtistSignature,
    name: String,
    palette: ArtworkPalette,
    horizontalPadding: Dp,
) {
    val context = LocalPlatformContext.current
    val isSvg = signature.format == "svg"
    val aspect = signature.aspect.coerceIn(0.4f, 6f)
    val request = remember(signature.imageUrl) {
        ImageRequest.Builder(context).data(signature.imageUrl).apply {
            if (isSvg) {
                size(SVG_DECODE_WIDTH, (SVG_DECODE_WIDTH / aspect).roundToInt())
            } else {
                size(coil3.size.Size.ORIGINAL)
                transformations(SignatureInkTransformation)
            }
        }.build()
    }
    var loaded by remember(signature.imageUrl) { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding),
        horizontalAlignment = Alignment.Start,
    ) {
        AsyncImage(
            model = request,
            contentDescription = name,
            contentScale = ContentScale.Fit,
            colorFilter = if (isSvg) ColorFilter.tint(Color.White) else null,
            onState = { if (it is AsyncImagePainter.State.Success) loaded = true },
            modifier = Modifier
                .padding(top = 20.dp, bottom = 6.dp)
                .height(ABOUT_LOGO_HEIGHT)
                .aspectRatio(aspect),
        )
        if (loaded) SignedByCaption(name = name, palette = palette, textAlign = TextAlign.Start)
    }
}

