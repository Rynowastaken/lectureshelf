package com.ryno.classrecorder.ui.components

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ryno.classrecorder.ui.AppearancePreferences
import com.ryno.classrecorder.ui.CustomBackground
import com.ryno.classrecorder.ui.CustomBackgroundRevision
import com.ryno.classrecorder.ui.theme.AppSpace
import com.ryno.classrecorder.ui.theme.ClassRecorderTheme
import com.ryno.classrecorder.ui.theme.DshSurfaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Computes `(startInset, endInset)` combining centered `AppSpace.readingWidth` (`720.dp`)
 * margins with landscape `displayCutout` and horizontal `navigationBars` safe insets.
 */
@Composable
fun rememberSafeSideInsets(maxWidth: Dp): Pair<Dp, Dp> {
    val layoutDirection = LocalLayoutDirection.current
    val safeHorizontal = WindowInsets.displayCutout
        .union(WindowInsets.navigationBars)
        .asPaddingValues()
    val centeredInset = ((maxWidth - AppSpace.readingWidth) / 2f).coerceAtLeast(0.dp)
    val start = maxOf(centeredInset, safeHorizontal.calculateStartPadding(layoutDirection))
    val end = maxOf(centeredInset, safeHorizontal.calculateEndPadding(layoutDirection))
    return start to end
}

/**
 * Reports normalized `0f..1f` scroll-under progress for a root tab's [LazyListState]
 * so the top [BackdropVeil] fades in smoothly once list content passes beneath the Chrome bar.
 */
@Composable
fun BindScrollUnderVeil(
    listState: LazyListState,
    onScrolledUnderChange: (Float) -> Unit
) {
    val density = LocalDensity.current
    val scrollFraction by remember(density) {
        val fadePx = with(density) { 28.dp.toPx() }
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / fadePx).coerceIn(0f, 1f)
        }
    }
    LaunchedEffect(scrollFraction) {
        onScrolledUnderChange(scrollFraction)
    }
}

/**
 * Full-bleed DSH Go page backdrop: vertical Mist/Slate gradient + two wide
 * low-alpha corner radial glows cached per canvas size.
 */
@Composable
fun DshBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val s = ClassRecorderTheme.surfaces
    // A user-chosen photo replaces the gradient. It is drawn UNDER the glows so
    // the accent still tints it, and under a scrim so glass cards and body text
    // keep their contrast - a bright holiday photo would otherwise wash out
    // every label on the page.
    val context = LocalContext.current
    var customImage by remember { mutableStateOf<ImageBitmap?>(null) }

    // Reload whenever the preference changes, so picking or clearing a picture
    // in Settings updates every screen without a restart.
    val revision = CustomBackgroundRevision.value
    val preferenceKey = remember(revision) {
        AppearancePreferences.loadBackgroundPath(context) to
            AppearancePreferences.loadBackgroundBlur(context)
    }
    // Re-derives the blurred bitmap whenever the photo OR the slider changes.
    // Debounced in the UI (onValueChangeFinished) so dragging does not decode a
    // fresh bitmap on every pixel of travel.
    LaunchedEffect(preferenceKey) {
        val (_, strength) = preferenceKey
        customImage = withContext(Dispatchers.IO) {
            CustomBackground.load(context, strength)
        }
    }

    // A second graphics layer that holds ONLY the backdrop. Published via
    // LocalBackdropLayer so every glass card samples the wallpaper behind it,
    // not the scroll content it happens to be sitting on.
    // ONE graphics layer holds the whole backdrop - wallpaper, scrim, gradient,
    // glows - matching how dsh-Go paints it. That layer is simultaneously:
    //   1. what is drawn to the screen, and
    //   2. what every glass card samples via LocalBackdropLayer.
    // So glass refracts the wallpaper that is genuinely behind it, and there is
    // no second opaque copy covering the photo.
    val backdropSampleLayer = rememberGraphicsLayer()
    val photo = customImage

    // Scrim strength adapts to how BRIGHT the photo is, not its average: an
    // illustration can average 0.65 while its bright regions sit near 241/255,
    // and those are what swallow white text.
    val bright = remember(photo) { photo?.brightLuminance() ?: 0f }
    val lightMode = s.backdropTop.luminance() > 0.5f
    // Lighter than the un-blurred case: a blurred photo no longer produces sharp
    // bright edges, so it needs less help to sit behind text. The old values
    // turned the wallpaper into mud.
    val scrim = if (lightMode) {
        (0.18f + bright * 0.30f).coerceIn(0.18f, 0.52f)
    } else {
        (0.22f + bright * 0.30f).coerceIn(0.22f, 0.55f)
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Record ONLY the backdrop (wallpaper + scrim + gradient + glows) in a
        // dedicated sibling Box so foreground GlassSurfaces sampling
        // LocalBackdropLayer can never create a self-referential RenderNode cycle
        // (which otherwise overflows RenderThread's stack in prepareTreeImpl).
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawWithContent {
                    backdropSampleLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(backdropSampleLayer)
                }
                .drawWithCache {
                    val hasPhoto = photo != null
                    val tintAlpha = if (hasPhoto) 0.42f else 1f
                    val bgBrush = Brush.verticalGradient(
                        colors = listOf(
                            s.backdropTop.copy(alpha = tintAlpha),
                            s.backdropBottom.copy(alpha = tintAlpha)
                        ),
                        startY = 0f,
                        endY = size.height
                    )
                    val longest = max(size.width, size.height)
                    val center1 = Offset(size.width * 0.92f, 0f)
                    val radius1 = (longest * 0.62f).coerceAtLeast(1f)
                    val glow1 = Brush.radialGradient(
                        colors = listOf(s.glowPrimary, s.glowPrimary.copy(alpha = 0f)),
                        center = center1,
                        radius = radius1
                    )
                    val center2 = Offset(0f, size.height)
                    val radius2 = (longest * 0.55f).coerceAtLeast(1f)
                    val glow2 = Brush.radialGradient(
                        colors = listOf(s.glowSecondary, s.glowSecondary.copy(alpha = 0f)),
                        center = center2,
                        radius = radius2
                    )
                    onDrawBehind {
                        val image = photo
                        if (image != null) {
                            drawImageCover(image)
                            drawRect(Color.Black.copy(alpha = scrim))
                        }
                        drawRect(brush = bgBrush)
                        if (longest > 0f) {
                            drawCircle(brush = glow1, radius = radius1, center = center1)
                            drawCircle(brush = glow2, radius = radius2, center = center2)
                        }
                    }
                }
        )

        CompositionLocalProvider(LocalBackdropLayer provides backdropSampleLayer) {
            Box(
                modifier = Modifier.fillMaxSize(),
                content = content
            )
        }
    }
}

/**
 * The 85th-percentile relative luminance of [image], sampled on a coarse grid.
 *
 * Deliberately NOT the mean. An illustration can average 0.65 while its bright
 * regions sit at 241/255, and it is those bright patches that swallow white body
 * text - a mean scrim leaves exactly the worst spots under-protected. The 85th
 * percentile asks "how bright is the part that will actually fight the text".
 */
private fun ImageBitmap.brightLuminance(): Float {
    if (width <= 0 || height <= 0) return 0.5f
    val bitmap = this.asAndroidBitmap()
    val samples = ArrayList<Float>(576)
    val stepX = (width / 24).coerceAtLeast(1)
    val stepY = (height / 24).coerceAtLeast(1)
    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            val pixel = bitmap.getPixel(x, y)
            // Rec. 709 luma; a chosen photo is always opaque.
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            samples += ((0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0).toFloat()
            x += stepX
        }
        y += stepY
    }
    if (samples.isEmpty()) return 0.5f
    samples.sort()
    return samples[((samples.size - 1) * 0.85f).toInt()]
}

/**
 * Draw [image] so it covers the whole canvas without distortion (the CSS
 * `background-size: cover` behaviour): scale by the larger ratio, centre it,
 * and let the overflow crop.
 */
private fun DrawScope.drawImageCover(image: ImageBitmap) {
    val iw = image.width.toFloat()
    val ih = image.height.toFloat()
    if (iw <= 0f || ih <= 0f) return
    val scale = max(size.width / iw, size.height / ih)
    val drawW = iw * scale
    val drawH = ih * scale
    drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(
            ((size.width - drawW) / 2f).roundToInt(),
            ((size.height - drawH) / 2f).roundToInt()
        ),
        dstSize = IntSize(drawW.roundToInt(), drawH.roundToInt()),
        filterQuality = FilterQuality.Medium
    )
}

/**
 * Feathered backdrop band placed behind floating top/bottom chrome once content
 * scrolls underneath. Samples the live [LocalBackdropLayer] (wallpaper + scrim +
 * gradient + glows) masked with an 8-stop `easeInOutCubic` alpha ramp so there is
 * never a flat-color mismatch or hard linear crease cutting across scrolled controls.
 */
@Composable
fun BackdropVeil(
    fromTop: Boolean,
    solidHeight: Dp,
    feather: Dp = 28.dp,
    alpha: Float = 1f,
    modifier: Modifier = Modifier
) {
    val clampedAlpha = alpha.coerceIn(0f, 1f)
    if (clampedAlpha <= 0.001f) return
    val s = ClassRecorderTheme.surfaces
    val backdropLayer = LocalBackdropLayer.current
    val totalHeight = solidHeight + feather
    if (totalHeight <= 0.dp) return

    var rootOffset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(totalHeight)
            .then(
                if (!fromTop && backdropLayer != null) {
                    Modifier.onGloballyPositioned { coords ->
                        val next = coords.positionInRoot()
                        if (kotlin.math.abs(next.x - rootOffset.x) > 0.5f ||
                            kotlin.math.abs(next.y - rootOffset.y) > 0.5f
                        ) {
                            rootOffset = next
                        }
                    }
                } else Modifier
            )
            .graphicsLayer {
                this.alpha = clampedAlpha
                if (backdropLayer != null) {
                    compositingStrategy = CompositingStrategy.Offscreen
                }
            }
            .drawWithCache {
                val bandH = size.height
                val featherFrac = if (bandH > 0f) (feather.toPx() / bandH).coerceIn(0f, 1f) else 0f
                val samples = 8
                val stopList = ArrayList<Pair<Float, Color>>(samples + 3)

                fun easeInOutCubic(t: Float): Float =
                    if (t < 0.5f) 4f * t * t * t
                    else 1f - ((-2f * t + 2f) * (-2f * t + 2f) * (-2f * t + 2f)) / 2f

                val fallbackColor = if (fromTop) s.backdropTop else s.backdropBottom
                val maskBase = if (backdropLayer != null) Color.Black else fallbackColor

                if (fromTop && featherFrac < 1f) {
                    stopList.add(0f to maskBase)
                }
                for (i in 0..samples) {
                    val t = i.toFloat() / samples.toFloat()
                    val pos = if (fromTop) {
                        (1f - featherFrac) + featherFrac * t
                    } else {
                        featherFrac * t
                    }.coerceIn(0f, 1f)
                    val eased = easeInOutCubic(t)
                    val stopAlpha = if (fromTop) 1f - eased else eased
                    stopList.add(pos to maskBase.copy(alpha = stopAlpha))
                }
                if (!fromTop && featherFrac < 1f) {
                    stopList.add(1f to maskBase)
                }

                val maskBrush = Brush.verticalGradient(*stopList.toTypedArray())

                onDrawBehind {
                    if (bandH <= 0f) return@onDrawBehind
                    if (backdropLayer != null) {
                        val ox = if (fromTop) 0f else rootOffset.x
                        val oy = if (fromTop) 0f else rootOffset.y
                        translate(left = -ox, top = -oy) {
                            drawLayer(backdropLayer)
                        }
                        drawRect(brush = maskBrush, blendMode = BlendMode.DstIn)
                    } else {
                        drawRect(brush = maskBrush)
                    }
                }
            }
    )
}

internal fun DrawScope.drawBackdropLayer(
    canvasSize: Size,
    surfaces: DshSurfaces
) {
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(surfaces.backdropTop, surfaces.backdropBottom),
            startY = 0f,
            endY = canvasSize.height
        ),
        size = canvasSize
    )
    val longest = max(canvasSize.width, canvasSize.height)
    if (longest > 0f) {
        val center1 = Offset(canvasSize.width * 0.92f, 0f)
        val radius1 = longest * 0.62f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(surfaces.glowPrimary, surfaces.glowPrimary.copy(alpha = 0f)),
                center = center1,
                radius = radius1
            ),
            radius = radius1,
            center = center1
        )
        val center2 = Offset(0f, canvasSize.height)
        val radius2 = longest * 0.55f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(surfaces.glowSecondary, surfaces.glowSecondary.copy(alpha = 0f)),
                center = center2,
                radius = radius2
            ),
            radius = radius2,
            center = center2
        )
    }
}
