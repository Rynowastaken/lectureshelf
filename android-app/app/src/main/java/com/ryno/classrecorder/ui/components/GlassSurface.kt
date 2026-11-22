package com.ryno.classrecorder.ui.components

import android.graphics.BlurMaskFilter
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.ryno.classrecorder.ui.theme.AppShape
import com.ryno.classrecorder.ui.theme.ClassRecorderTheme
import com.ryno.classrecorder.ui.theme.DshMotion
import com.ryno.classrecorder.ui.theme.GlassShadow
import com.ryno.classrecorder.ui.theme.GlassTier
import kotlin.math.abs
import kotlin.math.max

val LocalBackdropLayer = compositionLocalOf<GraphicsLayer?> { null }
val LocalWindowBlurActive = compositionLocalOf { false }

/**
 * Maximum number of glass surfaces allowed to run their own offscreen blur.
 *
 * Every blurred card creates a graphicsLayer with a RenderEffect and draws the
 * whole backdrop into it. That is fine for a handful of static cards, but a
 * dismissal (confirm sheet out, recorder subtree gone) can animate dozens at
 * once in a single frame - each re-recording a full-screen layer. libhwui then
 * overflows the render thread's stack: SIGSEGV with ~150 nested
 * RenderNode::prepareTreeImpl frames and "stack pointer is close to top of stack".
 *
 * Cards past the budget fall back to the tier's opaque fill, which looks
 * identical at rest and costs nothing.
 */
private val LocalGlassBlurBudget = compositionLocalOf { 6 }

/**
 * How many glass surfaces currently hold a blur slot. A plain Compose state so
 * claiming one triggers the recomposition that actually enables the effect.
 */
private val mutableBlurCount = androidx.compose.runtime.mutableIntStateOf(0)

/** Wraps a subtree so its glass surfaces share a blur budget. */
@Composable
fun ProvideGlassBlurBudget(budget: Int = 6, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGlassBlurBudget provides budget, content = content)
}

/**
 * Four-tier DSH Go glass surface with cached outer shadows, cached edge hairlines/rims,
 * and Android 12+ GraphicsLayer / Window backdrop blur for Chrome & Sheet tiers.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    tier: GlassTier = GlassTier.Card,
    shape: Shape? = null,
    circle: Boolean = false,
    stadium: Boolean = false,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable BoxScope.() -> Unit
) {
    val surfaces = ClassRecorderTheme.surfaces
    val reducedMotion = ClassRecorderTheme.reducedMotion
    val density = LocalDensity.current
    val backdropLayer = LocalBackdropLayer.current
    val windowBlurActive = LocalWindowBlurActive.current
    val s = surfaces.glass(tier)

    val resolvedShape: Shape = when {
        circle -> CircleShape
        stadium -> AppShape.pill
        shape != null -> shape
        else -> when (tier) {
            GlassTier.Chrome -> AppShape.radiusXl
            GlassTier.Sheet -> AppShape.sheetTop
            GlassTier.Card -> AppShape.radiusMd
            GlassTier.Pill -> AppShape.pill
        }
    }

    // Claim a slot in the shared blur budget ONLY for surfaces that actually
    // run an offscreen RenderEffect blur (GlassTier.Chrome). Card and Pill
    // tiers have blurSigma == 0 and must not consume slots needed by Chrome bars.
    val wantsLayerBlur = s.blurs &&
        surfaces.blurEnabled &&
        !reducedMotion &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        backdropLayer != null &&
        tier != GlassTier.Sheet
    val budget = LocalGlassBlurBudget.current
    var claimed by remember { mutableStateOf(false) }
    DisposableEffect(wantsLayerBlur) {
        if (wantsLayerBlur && mutableBlurCount.value < budget) {
            mutableBlurCount.value++
            claimed = true
        }
        onDispose {
            if (claimed) {
                mutableBlurCount.value--
                claimed = false
            }
        }
    }

    val canSampleLayerBlur = wantsLayerBlur && claimed

    val canUseWindowBlur = s.blurs &&
        surfaces.blurEnabled &&
        !reducedMotion &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        windowBlurActive &&
        tier == GlassTier.Sheet

    val fill = when {
        !s.blurs -> if (surfaces.blurEnabled) s.fill else s.fallbackFill
        canSampleLayerBlur || canUseWindowBlur -> s.fill
        else -> s.fallbackFill
    }

    var rootOffset by remember { mutableStateOf(Offset.Zero) }
    val blurRadiusPx = remember(s.blurSigma, density) {
        with(density) { s.blurSigma.dp.toPx() }
    }
    val cachedBlurEffect = remember(canSampleLayerBlur, blurRadiusPx) {
        if (canSampleLayerBlur && blurRadiusPx > 0.5f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BlurEffect(
                radiusX = blurRadiusPx,
                radiusY = blurRadiusPx,
                edgeTreatment = TileMode.Clamp
            )
        } else null
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val interactive = enabled && onClick != null
    val scale by animateFloatAsState(
        targetValue = if (interactive && isPressed && !reducedMotion) DshMotion.PRESS_SCALE else 1f,
        animationSpec = DshMotion.pressSpring(reducedMotion),
        label = "glassPressScale"
    )

    val clickModifier = if (enabled && onClick != null) {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = ripple(),
            onClick = onClick
        )
    } else Modifier

    Box(
        propagateMinConstraints = true,
        modifier = modifier
            .then(
                if (cachedBlurEffect != null) {
                    Modifier.onGloballyPositioned { coords ->
                        val next = coords.positionInRoot()
                        if (abs(next.x - rootOffset.x) > 0.5f || abs(next.y - rootOffset.y) > 0.5f) {
                            rootOffset = next
                        }
                    }
                } else Modifier
            )
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .drawOuterGlassShadows(shape = resolvedShape, shadows = s.shadows)
            .clip(resolvedShape)
            .then(clickModifier)
            .drawGlassEdges(
                shape = resolvedShape,
                hairline = s.hairline,
                hairlineWidthDp = s.hairlineWidth.value,
                rim = s.rim
            )
    ) {
        if (cachedBlurEffect != null && backdropLayer != null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        renderEffect = cachedBlurEffect
                    }
                    .drawBehind {
                        // Blit the backdrop layer at the card's offset. The layer
                        // ALREADY holds the gradient and both glows, so redrawing
                        // them here (as this used to) duplicated full-screen work
                        // once per blurred card, every frame - that is what made
                        // animations render at 150ms/frame.
                        translate(left = -rootOffset.x, top = -rootOffset.y) {
                            drawLayer(backdropLayer)
                        }
                    }
            )
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(fill)
        )
        Box(modifier = Modifier.padding(contentPadding)) {
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSurface,
                LocalBackdropLayer provides if (canSampleLayerBlur) null else backdropLayer
            ) {
                content()
            }
        }
    }
}

private fun Modifier.drawGlassEdges(
    shape: Shape,
    hairline: Color,
    hairlineWidthDp: Float,
    rim: Color
): Modifier = this.drawWithCache {
    if (size.isEmpty()) {
        return@drawWithCache onDrawWithContent { drawContent() }
    }
    val strokePx = max(hairlineWidthDp.dp.toPx(), 1f)
    val inset = strokePx * 0.5f
    val innerSize = Size(
        width = (size.width - strokePx).coerceAtLeast(0f),
        height = (size.height - strokePx).coerceAtLeast(0f)
    )
    if (innerSize.isEmpty()) {
        return@drawWithCache onDrawWithContent { drawContent() }
    }

    val outline = shape.createOutline(innerSize, layoutDirection, this)
    val path = outlineToPath(outline, offsetX = inset, offsetY = inset)
    val hairlineStroke = Stroke(width = strokePx)
    val rimStroke = Stroke(width = max(strokePx, 1.1f))
    val rimBrush = if (rim.alpha > 0f) {
        Brush.verticalGradient(
            0f to rim,
            0.45f to rim.copy(alpha = 0f),
            startY = 0f,
            endY = size.height
        )
    } else null

    onDrawWithContent {
        drawContent()
        if (hairline.alpha > 0f) {
            drawPath(path = path, color = hairline, style = hairlineStroke)
        }
        if (rimBrush != null) {
            drawPath(path = path, brush = rimBrush, style = rimStroke)
        }
    }
}

private class CachedShadowEntry(
    val path: android.graphics.Path,
    val paint: android.graphics.Paint
)

private fun Modifier.drawOuterGlassShadows(
    shape: Shape,
    shadows: List<GlassShadow>
): Modifier {
    if (shadows.isEmpty()) return this
    return this.drawWithCache {
        if (size.isEmpty()) {
            return@drawWithCache onDrawBehind {}
        }
        val shapeOutline = shape.createOutline(size, layoutDirection, this)
        val innerPath = outlineToPath(shapeOutline, 0f, 0f)
        var maxExtent = 0f
        for (sh in shadows) {
            maxExtent = max(maxExtent, sh.blurRadius.toPx() * 2.2f + sh.offsetY.toPx())
        }
        val outerBounds = Path().apply {
            addRect(
                Rect(
                    left = -maxExtent,
                    top = -maxExtent,
                    right = size.width + maxExtent,
                    bottom = size.height + maxExtent
                )
            )
        }
        val exteriorClip = Path.combine(PathOperation.Difference, outerBounds, innerPath).asAndroidPath()
        val entries = shadows.mapNotNull { sh ->
            if (sh.color.alpha <= 0f) return@mapNotNull null
            val blurPx = sh.blurRadius.toPx()
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.FILL
                color = sh.color.toArgb()
                if (blurPx > 0.5f) {
                    maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
                }
            }
            val shiftedPath = outlineToPath(shapeOutline, 0f, sh.offsetY.toPx()).asAndroidPath()
            CachedShadowEntry(shiftedPath, paint)
        }

        onDrawBehind {
            if (entries.isEmpty()) return@onDrawBehind
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                val saveCount = native.save()
                native.clipPath(exteriorClip)
                for (entry in entries) {
                    native.drawPath(entry.path, entry.paint)
                }
                native.restoreToCount(saveCount)
            }
        }
    }
}

private fun outlineToPath(outline: Outline, offsetX: Float, offsetY: Float): Path = Path().apply {
    when (outline) {
        is Outline.Rectangle -> addRect(outline.rect.translate(offsetX, offsetY))
        is Outline.Rounded -> addRoundRect(
            RoundRect(
                rect = outline.roundRect.let {
                    Rect(it.left + offsetX, it.top + offsetY, it.right + offsetX, it.bottom + offsetY)
                },
                topLeft = outline.roundRect.topLeftCornerRadius,
                topRight = outline.roundRect.topRightCornerRadius,
                bottomRight = outline.roundRect.bottomRightCornerRadius,
                bottomLeft = outline.roundRect.bottomLeftCornerRadius
            )
        )
        is Outline.Generic -> addPath(outline.path, Offset(offsetX, offsetY))
    }
}
