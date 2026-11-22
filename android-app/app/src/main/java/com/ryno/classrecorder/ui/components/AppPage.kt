package com.ryno.classrecorder.ui.components

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.ryno.classrecorder.ui.theme.AppSpace
import com.ryno.classrecorder.ui.theme.ClassRecorderTheme
import com.ryno.classrecorder.ui.theme.DshMotion
import com.ryno.classrecorder.ui.theme.GlassTier
import com.ryno.classrecorder.ui.theme.tabular
import kotlinx.coroutines.CancellationException

/**
 * Secondary full-screen page frame ported from DSH Go's AppPage in page_frame.dart.
 *
 * Includes built-in Android predictive-back gesture handling, TalkBack & pointer isolation,
 * live GPU [rememberGraphicsLayer] capture for Chrome blur, and feathered system-bar veils.
 */
@Composable
fun AppPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    hero: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit
) {
    val density = LocalDensity.current
    val reduced = ClassRecorderTheme.reducedMotion
    val listState = rememberLazyListState()
    val backdropLayer = rememberGraphicsLayer()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val barHeight = 56.dp
    val topReserved = statusTop + barHeight

    var backProgress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler { progress ->
        try {
            progress.collect { event -> backProgress = event.progress }
            backProgress = 0f
            onBack()
        } catch (_: CancellationException) {
            backProgress = 0f
        }
    }

    val defaultHeaderPx = with(density) { 72.dp.toPx() }
    var headerHeightPx by remember { mutableFloatStateOf(defaultHeaderPx) }

    val scrollOffsetPx by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex == 0) {
                listState.firstVisibleItemScrollOffset.toFloat()
            } else {
                headerHeightPx + 1000f
            }
        }
    }

    val headerAlpha = (1f - scrollOffsetPx / (headerHeightPx * 0.6f).coerceAtLeast(1f))
        .coerceIn(0f, 1f)
    val barTitleRisePx = with(density) { 20.dp.toPx() }
    val barTitleProgress = ((scrollOffsetPx - headerHeightPx * 0.4f) / barTitleRisePx)
        .coerceIn(0f, 1f)
    val veilFadePx = with(density) { 24.dp.toPx() }
    val veilAlpha = (scrollOffsetPx / veilFadePx).coerceIn(0f, 1f)
    val titleSlideOffsetPx = with(density) { 6.dp.toPx() } * (1f - barTitleProgress)

    val canScrollDown by remember { derivedStateOf { listState.canScrollForward } }
    val bottomVeilAlpha by animateFloatAsState(
        targetValue = if (canScrollDown) 0.94f else 0f,
        animationSpec = DshMotion.quickTween(reduced),
        label = "appPageBottomVeil"
    )

    DshBackdrop(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                if (!reduced && backProgress > 0f) {
                    translationX = 40.dp.toPx() * backProgress
                    val s = 1f - 0.05f * backProgress
                    scaleX = s
                    scaleY = s
                    alpha = 1f - 0.18f * backProgress
                }
            }
            .semantics { isTraversalGroup = true }
        // NOTE: there used to be a `pointerInput { awaitPointerEventScope { ... consume() } }`
        // here. It consumed EVERY pointer event on the page root, which stopped the
        // LazyColumn inside from ever seeing a drag - scrolling was dead on every
        // screen that used AppPage, Settings most visibly because its body is the
        // longest. It was never wired to anything: the back-swipe visual below is
        // driven by PredictiveBackHandler, not by this gesture detector.
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val (startInset, endInset) = rememberSafeSideInsets(maxWidth)

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        backdropLayer.record {
                            this@drawWithContent.drawContent()
                        }
                        drawLayer(backdropLayer)
                    },
                contentPadding = PaddingValues(
                    start = startInset,
                    end = endInset,
                    top = topReserved,
                    bottom = navBottom + 44.dp
                )
            ) {
                item(key = "__app_page_header__") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { sz ->
                                if (sz.height > 0) {
                                    headerHeightPx = sz.height.toFloat().coerceIn(
                                        with(density) { 40.dp.toPx() },
                                        with(density) { 420.dp.toPx() }
                                    )
                                }
                            }
                            .graphicsLayer {
                                alpha = headerAlpha
                            }
                    ) {
                        if (hero != null) {
                            hero()
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 24.dp, top = 6.dp, end = 24.dp, bottom = 20.dp)
                            ) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.headlineLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.semantics { heading() }
                                )
                            }
                        }
                    }
                }

                content()
            }

            BackdropVeil(
                fromTop = true,
                solidHeight = statusTop,
                feather = 28.dp,
                alpha = veilAlpha,
                modifier = Modifier.align(Alignment.TopCenter)
            )

            BackdropVeil(
                fromTop = false,
                solidHeight = 0.dp,
                feather = navBottom + 4.dp,
                alpha = bottomVeilAlpha * 0.65f,
                modifier = Modifier.align(Alignment.BottomCenter)
            )

            CompositionLocalProvider(LocalBackdropLayer provides backdropLayer) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = statusTop)
                        .fillMaxWidth()
                        .height(barHeight)
                        .padding(
                            start = AppSpace.floatInset + startInset,
                            end = AppSpace.floatInset + endInset
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassIconButton(
                        icon = DshIcons.ArrowBack,
                        contentDescription = "Back",
                        onClick = onBack
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (barTitleProgress > 0.01f) {
                            GlassSurface(
                                tier = GlassTier.Chrome,
                                stadium = true,
                                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 9.dp),
                                modifier = Modifier.graphicsLayer {
                                    alpha = barTitleProgress
                                    translationY = titleSlideOffsetPx
                                }
                            ) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier.sizeIn(minWidth = 48.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            content = actions
                        )
                    }
                }
            }
        }
    }
}

/**
 * Detail-page header ported from DSH Go's PageHero in page_hero.dart.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PageHero(
    title: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    subtitle: String? = null,
    tags: List<String> = emptyList(),
    stats: List<Pair<String, String>> = emptyList()
) {
    val dsh = ClassRecorderTheme.dshColors
    val scheme = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 24.dp, top = 4.dp, end = 24.dp, bottom = 20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(16.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = scheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() }
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium.tabular(),
                        color = dsh.textTertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (tags.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                tags.forEach { rawTag ->
                    val label = if (rawTag.startsWith("#")) rawTag else "#$rawTag"
                    QuietTagPill(text = label)
                }
            }
        }

        if (stats.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                stats.forEach { (value, label) ->
                    HeroStat(
                        value = value,
                        label = label,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

private val NumberThenUnitRegex = Regex("""^([0-9][0-9.,:]*)(\s*)([^0-9\s.,:+\-].*)$""")

@Composable
private fun HeroStat(
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    val dsh = ClassRecorderTheme.dshColors
    val numberStyle = MaterialTheme.typography.titleLarge.tabular()
    val unitStyle = MaterialTheme.typography.labelMedium.copy(
        color = dsh.textTertiary,
        fontWeight = FontWeight.Normal
    )
    val match = NumberThenUnitRegex.matchEntire(value.trim())

    Column(modifier = modifier) {
        if (match == null) {
            Text(
                text = value,
                style = numberStyle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            val numPart = match.groupValues[1]
            val unitPart = match.groupValues[2] + match.groupValues[3]
            Text(
                text = buildAnnotatedString {
                    withStyle(
                        SpanStyle(
                            fontSize = numberStyle.fontSize,
                            fontWeight = numberStyle.fontWeight,
                            fontFeatureSettings = "tnum",
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        append(numPart)
                    }
                    withStyle(
                        SpanStyle(
                            fontSize = unitStyle.fontSize,
                            fontWeight = FontWeight.Normal,
                            color = dsh.textTertiary
                        )
                    ) {
                        append(unitPart)
                    }
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = dsh.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
