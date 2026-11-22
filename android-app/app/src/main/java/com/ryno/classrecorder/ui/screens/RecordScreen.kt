package com.ryno.classrecorder.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ryno.classrecorder.core.CourseSlot
import com.ryno.classrecorder.core.RecordingRecord
import com.ryno.classrecorder.service.RecorderState
import com.ryno.classrecorder.ui.MainUiState
import com.ryno.classrecorder.ui.components.AppSheet
import com.ryno.classrecorder.ui.components.BackdropVeil
import com.ryno.classrecorder.ui.components.BindScrollUnderVeil
import com.ryno.classrecorder.ui.components.CapsuleChoiceChip
import com.ryno.classrecorder.ui.components.DshConfirmSheet
import com.ryno.classrecorder.ui.components.DshIcons
import com.ryno.classrecorder.ui.components.DshPrimaryButton
import com.ryno.classrecorder.ui.components.DshSecondaryButton
import com.ryno.classrecorder.ui.components.GlassPill
import com.ryno.classrecorder.ui.components.GlassSurface
import com.ryno.classrecorder.ui.components.ListCardDivider
import com.ryno.classrecorder.ui.components.ListPageRow
import com.ryno.classrecorder.ui.components.LocalBackdropLayer
import com.ryno.classrecorder.ui.components.OverviewCard
import com.ryno.classrecorder.ui.components.OverviewFooterRow
import com.ryno.classrecorder.ui.components.OverviewGlyph
import com.ryno.classrecorder.ui.components.SettingsIcon
import com.ryno.classrecorder.ui.components.SheetGroup
import com.ryno.classrecorder.ui.components.StatusChip
import com.ryno.classrecorder.ui.components.StatusDot
import com.ryno.classrecorder.ui.components.rememberSafeSideInsets
import com.ryno.classrecorder.ui.theme.AppShape
import com.ryno.classrecorder.ui.theme.AppSpace
import com.ryno.classrecorder.ui.theme.ClassRecorderTheme
import com.ryno.classrecorder.ui.theme.DshMotion
import com.ryno.classrecorder.ui.theme.GlassTier
import com.ryno.classrecorder.ui.theme.StatusTone
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class CaptureSessionHeader(
    val isRecording: Boolean,
    val isPaused: Boolean,
    val activeRecording: RecordingRecord?
)

/**
 * Primary lecture capture workspace with isolated 1 Hz clock & 30 fps draw-only waveform
 * so live capture never recomposes cards, chips, or GPU backdrop layers.
 */
@Composable
fun RecordScreen(
    ui: MainUiState,
    recorderState: StateFlow<RecorderState>,
    hasMicPermission: Boolean,
    onRequestMicPermission: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onDiscardRecording: () -> Unit,
    onTogglePause: () -> Unit,
    onSelectCourseOverride: (CourseSlot?) -> Unit,
    onOpenSchedule: () -> Unit = {},
    onToggleMcp: () -> Unit = {},
    onShowMessage: (String) -> Unit = {},
    topContentInset: Dp = 0.dp,
    onScrolledUnderChange: (Float) -> Unit = {}
) {
    val session by remember(recorderState) {
        recorderState.map {
            CaptureSessionHeader(
                isRecording = it.isRecording,
                isPaused = it.isPaused,
                activeRecording = it.activeRecording
            )
        }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(
        initialValue = recorderState.value.let {
            CaptureSessionHeader(it.isRecording, it.isPaused, it.activeRecording)
        }
    )

    val dsh = ClassRecorderTheme.dshColors
    val scheme = MaterialTheme.colorScheme
    val reduced = ClassRecorderTheme.reducedMotion
    val listState = rememberLazyListState()
    val contentBackdropLayer = rememberGraphicsLayer()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    var showCoursePicker by remember { mutableStateOf(false) }
    var showLocalMcpSheet by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var expandAgentCalls by remember { mutableStateOf(false) }

    BindScrollUnderVeil(listState = listState, onScrolledUnderChange = onScrolledUnderChange)

    val canScrollDown by remember { derivedStateOf { listState.canScrollForward } }
    val bottomVeilAlpha by animateFloatAsState(
        targetValue = if (canScrollDown) 0.92f else 0.72f,
        animationSpec = DshMotion.quickTween(reduced),
        label = "recordBottomVeil"
    )

    val distinctCourses = remember(ui.schedule) {
        ui.schedule?.slots
            .orEmpty()
            .flatMap { it.days }
            .filterNotNull()
            .filter { it.name.isNotBlank() }
            .distinctBy { it.name.trim() }
    }

    val activeCourseName = when {
        session.isRecording -> session.activeRecording?.courseName?.ifBlank { "Uncategorized" } ?: "Recording"
        ui.selectedCourseOverride != null -> ui.selectedCourseOverride.name
        ui.currentClass != null -> ui.currentClass.course.name
        else -> "Uncategorized"
    }

    val (statusTone, statusLabel) = when {
        session.isRecording && session.isPaused -> StatusTone.Warn to "Paused"
        session.isRecording -> StatusTone.Error to "Live"
        ui.selectedCourseOverride != null -> StatusTone.Busy to "Manual"
        ui.currentClass != null -> {
            if (ui.currentClass.kind == "upcoming") StatusTone.Busy to "Up next"
            else StatusTone.Ok to "In class"
        }
        else -> StatusTone.Idle to "Ready"
    }

    val courseAtoms: List<String> = when {
        session.isRecording -> {
            val r = session.activeRecording
            listOfNotNull(
                r?.slot?.takeIf { it.isNotBlank() }?.let { "P$it" },
                r?.room?.takeIf { it.isNotBlank() },
                r?.teacher?.takeIf { it.isNotBlank() }
            )
        }
        ui.selectedCourseOverride != null -> {
            val c = ui.selectedCourseOverride
            listOfNotNull(
                c.room.takeIf { it.isNotBlank() },
                c.teacher.takeIf { it.isNotBlank() }
            )
        }
        ui.currentClass != null -> {
            val cur = ui.currentClass
            listOfNotNull(
                cur.slot.takeIf { it.isNotBlank() }?.let { "P$it" },
                UiFormatters.formatTimeRange(cur.time).takeIf { it.isNotBlank() },
                cur.course.room.takeIf { it.isNotBlank() },
                cur.course.teacher.takeIf { it.isNotBlank() }
            )
        }
        else -> emptyList()
    }

    val fallbackSubtitle = when {
        courseAtoms.isNotEmpty() -> null
        session.isRecording -> "Capturing background audio"
        ui.selectedCourseOverride != null -> "Manual class override"
        distinctCourses.isNotEmpty() -> "No class in session · Tap to pick a course"
        else -> "Sync your schedule or record an untagged session"
    }

    val activeRoom = when {
        ui.selectedCourseOverride != null -> ui.selectedCourseOverride.room
        ui.currentClass != null -> ui.currentClass.course.room
        else -> ""
    }
    val activeTeacher = when {
        ui.selectedCourseOverride != null -> ui.selectedCourseOverride.teacher
        ui.currentClass != null -> ui.currentClass.course.teacher
        else -> ""
    }
    val activeSlot = ui.currentClass?.slot.orEmpty()
    var liveNowDate by remember { mutableStateOf(LocalDate.now()) }
    var liveNowTime by remember {
        mutableStateOf(LocalTime.now().format(DateTimeFormatter.ofPattern("HHmm", Locale.US)))
    }
    LaunchedEffect(Unit) {
        val hhmmFmt = DateTimeFormatter.ofPattern("HHmm", Locale.US)
        while (true) {
            liveNowDate = LocalDate.now()
            liveNowTime = LocalTime.now().format(hhmmFmt)
            delay(30_000L)
        }
    }
    val targetPathPreview = remember(
        ui.filenameTemplate,
        ui.dateFormat,
        activeCourseName,
        activeRoom,
        activeTeacher,
        activeSlot,
        liveNowDate,
        liveNowTime
    ) {
        val filePreview = UiFormatters.previewFilename(
            template = ui.filenameTemplate,
            dateFormat = ui.dateFormat,
            courseName = activeCourseName,
            room = activeRoom.ifBlank { "Room" },
            teacher = activeTeacher.ifBlank { "Instructor" },
            slot = activeSlot.ifBlank { "1" },
            date = liveNowDate,
            timeText = liveNowTime
        )
        val safeFolder = UiFormatters.sanitizePathSegment(activeCourseName)
        "$safeFolder/$filePreview"
    }

    val dockReservedHeight = navBottom + 96.dp

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val (startInset, endInset) = rememberSafeSideInsets(maxWidth)

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    contentBackdropLayer.record {
                        this@drawWithContent.drawContent()
                    }
                    drawLayer(contentBackdropLayer)
                },
            contentPadding = PaddingValues(
                start = AppSpace.gutter + startInset,
                end = AppSpace.gutter + endInset,
                top = topContentInset + 8.dp,
                bottom = dockReservedHeight + 20.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!hasMicPermission && !session.isRecording) {
                item(key = "mic_permission_banner") {
                    GlassSurface(
                        modifier = Modifier.fillMaxWidth(),
                        tier = GlassTier.Card,
                        shape = AppShape.radiusMd,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            SettingsIcon(
                                icon = DshIcons.MicOff,
                                danger = true
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Microphone access needed",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = scheme.onSurface
                                )
                                Text(
                                    text = "Grant audio permission to capture classroom lectures.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = dsh.textTertiary
                                )
                            }
                            DshSecondaryButton(
                                label = "Allow microphone",
                                onClick = onRequestMicPermission
                            )
                        }
                    }
                }
            }

            item(key = "course_overview") {
                OverviewCard(
                    leading = {
                        OverviewGlyph(
                            icon = DshIcons.School,
                            identityKey = activeCourseName
                        )
                    },
                    title = activeCourseName,
                    subtitle = fallbackSubtitle,
                    subtitleAtoms = courseAtoms,
                    titleBadge = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (ui.selectedCourseOverride != null && !session.isRecording) {
                                StatusChip(
                                    tone = StatusTone.Idle,
                                    label = "Auto",
                                    onClick = { onSelectCourseOverride(null) }
                                )
                            }
                            StatusChip(
                                tone = statusTone,
                                label = statusLabel
                            )
                        }
                    },
                    onClick = if (!session.isRecording) {
                        {
                            if (distinctCourses.isEmpty()) {
                                onOpenSchedule()
                            } else {
                                showCoursePicker = true
                            }
                        }
                    } else null,
                    footer = {
                        OverviewFooterRow(
                            tone = if (ui.mcpStatus.running) StatusTone.Ok else StatusTone.Idle,
                            text = if (ui.mcpStatus.running) {
                                "MCP · http://${ui.lanIp}:${ui.mcpStatus.port}/mcp"
                            } else {
                                "MCP · Server stopped"
                            }
                        )
                    },
                    onFooterClick = { showLocalMcpSheet = true }
                )
            }

            if (ui.mcpStatus.recentCalls.isNotEmpty()) {
                item(key = "mcp_recent_pill") {
                    val latest = ui.mcpStatus.recentCalls.last()
                    GlassPill(
                        onClick = { expandAgentCalls = !expandAgentCalls },
                        summary = {
                            StatusDot(tone = StatusTone.Busy, size = 7.dp)
                            Text(
                                text = "Agent · $latest",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = if (expandAgentCalls) DshIcons.ExpandLess else DshIcons.ExpandMore,
                                contentDescription = if (expandAgentCalls) "Collapse agent activity" else "Expand agent activity",
                                modifier = Modifier.size(18.dp),
                                tint = dsh.textTertiary
                            )
                        },
                        expandedContent = if (expandAgentCalls) {
                            {
                                ui.mcpStatus.recentCalls.takeLast(5).asReversed().forEach { call ->
                                    Text(
                                        text = call,
                                        style = ClassRecorderTheme.mono.monoSmall,
                                        color = scheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        } else null
                    )
                }
            }

            if (!session.isRecording && distinctCourses.isNotEmpty()) {
                item(key = "quick_courses") {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 2.dp)
                    ) {
                        item(key = "__auto__") {
                            CapsuleChoiceChip(
                                label = if (ui.currentClass != null) {
                                    "Auto (${ui.currentClass.course.name})"
                                } else {
                                    "Auto schedule"
                                },
                                selected = ui.selectedCourseOverride == null,
                                onClick = { onSelectCourseOverride(null) }
                            )
                        }
                        items(
                            items = distinctCourses,
                            key = { it.name }
                        ) { course ->
                            CapsuleChoiceChip(
                                label = course.name,
                                selected = ui.selectedCourseOverride?.name == course.name,
                                courseKey = course.name,
                                onClick = {
                                    if (ui.selectedCourseOverride?.name == course.name) {
                                        onSelectCourseOverride(null)
                                    } else {
                                        onSelectCourseOverride(course)
                                    }
                                }
                            )
                        }
                    }
                }
            }

            item(key = "capture_stage") {
                CaptureStageCard(
                    recorderState = recorderState,
                    isRecording = session.isRecording,
                    isPaused = session.isPaused,
                    hasMicPermission = hasMicPermission
                )
            }
        }

        BackdropVeil(
            fromTop = false,
            solidHeight = navBottom + 20.dp,
            feather = 36.dp,
            alpha = bottomVeilAlpha * 0.90f,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        CompositionLocalProvider(LocalBackdropLayer provides contentBackdropLayer) {
            CaptureComposerDock(
                activeCourseName = activeCourseName,
                targetPathPreview = targetPathPreview,
                isRecording = session.isRecording,
                isPaused = session.isPaused,
                hasMicPermission = hasMicPermission,
                onOpenCoursePicker = { showCoursePicker = true },
                onRequestMicPermission = onRequestMicPermission,
                onStart = onStartRecording,
                onTogglePause = onTogglePause,
                onFinish = onStopRecording,
                onDiscard = { showDiscardDialog = true },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = AppSpace.floatInset + startInset,
                        end = AppSpace.floatInset + endInset,
                        bottom = navBottom + AppSpace.floatInset
                    )
            )
        }
    }

    if (showCoursePicker) {
        CoursePickerSheet(
            courses = distinctCourses,
            selectedOverride = ui.selectedCourseOverride,
            currentClassName = ui.currentClass?.course?.name,
            onSelect = { picked ->
                onSelectCourseOverride(picked)
                showCoursePicker = false
            },
            onDismiss = { showCoursePicker = false }
        )
    }

    if (showLocalMcpSheet) {
        McpServerSheet(
            ui = ui,
            onDismiss = { showLocalMcpSheet = false },
            onToggleMcp = onToggleMcp,
            onCopied = onShowMessage
        )
    }

    if (showDiscardDialog) {
        DshConfirmSheet(
            title = "Discard recording?",
            message = "The current audio capture will be deleted and cannot be recovered.",
            confirmLabel = "Discard",
            cancelLabel = "Keep recording",
            danger = true,
            onConfirm = {
                showDiscardDialog = false
                onDiscardRecording()
            },
            onDismiss = { showDiscardDialog = false }
        )
    }
}

@Composable
private fun CaptureStageCard(
    recorderState: StateFlow<RecorderState>,
    isRecording: Boolean,
    isPaused: Boolean,
    hasMicPermission: Boolean
) {
    val dsh = ClassRecorderTheme.dshColors
    val mono = ClassRecorderTheme.mono
    val reduced = ClassRecorderTheme.reducedMotion

    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        tier = GlassTier.Card,
        shape = AppShape.radiusLg,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LiveTimerText(
                recorderState = recorderState,
                isRecording = isRecording,
                isPaused = isPaused
            )

            Spacer(Modifier.height(6.dp))

            AnimatedContent(
                targetState = when {
                    isRecording && isPaused -> "Recording paused"
                    isRecording -> "Capturing AAC 128 kbps · .m4a"
                    !hasMicPermission -> "Microphone permission required before recording"
                    else -> "Continues cleanly in the background with screen off"
                },
                transitionSpec = {
                    fadeIn(DshMotion.quickTween(reduced)) togetherWith fadeOut(DshMotion.quickTween(reduced))
                },
                label = "stageCaption"
            ) { caption ->
                Text(
                    text = caption,
                    style = mono.timerCaption,
                    color = dsh.textTertiary,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(20.dp))

            LiveRecordingWaveform(
                recorderState = recorderState,
                isRecording = isRecording,
                isPaused = isPaused,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(128.dp)
            )
        }
    }
}

@Composable
private fun LiveTimerText(
    recorderState: StateFlow<RecorderState>,
    isRecording: Boolean,
    isPaused: Boolean
) {
    val dsh = ClassRecorderTheme.dshColors
    val mono = ClassRecorderTheme.mono
    val scheme = MaterialTheme.colorScheme
    // Recompose at 1 Hz (seconds) instead of 30 Hz (milliseconds)
    val elapsedSec by remember(recorderState) {
        recorderState.map { if (it.isRecording) it.elapsedMs / 1000L else 0L }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = 0L)

    Text(
        text = UiFormatters.formatDuration(elapsedSec * 1000L),
        style = mono.timer,
        color = when {
            !isRecording -> dsh.textTertiary
            isPaused -> dsh.warning
            else -> scheme.onSurface
        }
    )
}

@Composable
private fun LiveRecordingWaveform(
    recorderState: StateFlow<RecorderState>,
    isRecording: Boolean,
    isPaused: Boolean,
    modifier: Modifier = Modifier
) {
    val dsh = ClassRecorderTheme.dshColors
    val ring = remember { FloatArray(96) }
    var writeCursor by remember { mutableIntStateOf(0) }
    var sampleCount by remember { mutableIntStateOf(0) }
    var frameTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(recorderState, isRecording) {
        if (!isRecording) {
            writeCursor = 0
            sampleCount = 0
            frameTick++
            return@LaunchedEffect
        }
        recorderState.collect { st ->
            if (st.isRecording && !st.isPaused) {
                ring[writeCursor] = st.amplitude.coerceIn(0.04f, 1f)
                writeCursor = (writeCursor + 1) % ring.size
                if (sampleCount < ring.size) sampleCount++
                frameTick++
            }
        }
    }

    LiveWaveformCanvas(
        ringBuffer = ring,
        writeCursor = writeCursor,
        sampleCount = sampleCount,
        frameTick = frameTick,
        active = isRecording,
        paused = isPaused,
        activeColor = if (isPaused) dsh.textTertiary else dsh.danger,
        idleColor = dsh.textTertiary,
        playheadColor = dsh.danger,
        modifier = modifier
    )
}

@Composable
private fun CaptureComposerDock(
    activeCourseName: String,
    targetPathPreview: String,
    isRecording: Boolean,
    isPaused: Boolean,
    hasMicPermission: Boolean,
    onOpenCoursePicker: () -> Unit,
    onRequestMicPermission: () -> Unit,
    onStart: () -> Unit,
    onTogglePause: () -> Unit,
    onFinish: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dsh = ClassRecorderTheme.dshColors
    val surfaces = ClassRecorderTheme.surfaces
    val scheme = MaterialTheme.colorScheme
    val reduced = ClassRecorderTheme.reducedMotion

    GlassSurface(
        modifier = modifier.fillMaxWidth(),
        tier = GlassTier.Chrome,
        shape = AppShape.radiusXl,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
    ) {
        AnimatedContent(
            targetState = isRecording,
            transitionSpec = {
                fadeIn(DshMotion.standardTween(reduced)) togetherWith fadeOut(DshMotion.quickTween(reduced))
            },
            label = "composerDockState"
        ) { active ->
            if (!active) {
                val dotColor = dsh.courseDot(activeCourseName, surfaces.darkTheme)
                val recInteraction = remember { MutableInteractionSource() }
                val recPressed by recInteraction.collectIsPressedAsState()
                val recScale by animateFloatAsState(
                    targetValue = if (recPressed && !reduced) DshMotion.PRESS_SCALE else 1f,
                    animationSpec = DshMotion.pressSpring(reduced),
                    label = "recordTriggerScale"
                )
                val triggerLabel = if (hasMicPermission) "Start recording" else "Allow microphone"

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .clip(AppShape.radiusLg)
                            .clickable(
                                role = Role.Button,
                                onClickLabel = "Choose course tag",
                                onClick = onOpenCoursePicker
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Record · $activeCourseName",
                                style = MaterialTheme.typography.titleSmall,
                                color = scheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (hasMicPermission) {
                                    targetPathPreview
                                } else {
                                    "Tap mic to allow audio permission"
                                },
                                style = ClassRecorderTheme.mono.monoSmall,
                                color = dsh.textTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .graphicsLayer {
                                scaleX = recScale
                                scaleY = recScale
                            }
                            .clip(CircleShape)
                            .background(dsh.danger)
                            .clickable(
                                interactionSource = recInteraction,
                                indication = ripple(),
                                role = Role.Button,
                                onClickLabel = triggerLabel,
                                onClick = if (hasMicPermission) onStart else onRequestMicPermission
                            )
                            .semantics {
                                contentDescription = triggerLabel
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = DshIcons.Mic,
                            contentDescription = null,
                            tint = dsh.onDanger,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GlassSurface(
                        modifier = Modifier.size(52.dp),
                        tier = GlassTier.Pill,
                        circle = true,
                        onClick = onDiscard
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = DshIcons.DeleteOutline,
                                contentDescription = "Discard recording",
                                tint = dsh.danger,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    if (isPaused) {
                        DshPrimaryButton(
                            label = "Resume",
                            icon = DshIcons.PlayArrow,
                            onClick = onTogglePause,
                            minHeight = 52.dp,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        DshSecondaryButton(
                            label = "Pause",
                            icon = DshIcons.Pause,
                            onClick = onTogglePause,
                            minHeight = 52.dp,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    DshPrimaryButton(
                        label = "Finish",
                        icon = DshIcons.Stop,
                        danger = true,
                        onClick = onFinish,
                        minHeight = 52.dp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun CoursePickerSheet(
    courses: List<CourseSlot>,
    selectedOverride: CourseSlot?,
    currentClassName: String?,
    onSelect: (CourseSlot?) -> Unit,
    onDismiss: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    AppSheet(
        onDismiss = onDismiss,
        title = "Tag recording course"
    ) {
        SheetGroup {
            ListPageRow(
                title = "Automatic from schedule",
                subtitle = currentClassName?.let { "Currently matches $it" }
                    ?: "Uses current timetable slot or Uncategorized",
                leading = {
                    SettingsIcon(icon = DshIcons.AutoAwesome, tintSlot = 0)
                },
                trailing = if (selectedOverride == null) {
                    {
                        Icon(
                            imageVector = DshIcons.Check,
                            contentDescription = "Selected",
                            tint = scheme.primary
                        )
                    }
                } else null,
                showChevron = false,
                onClick = { onSelect(null) }
            )
        }

        if (courses.isNotEmpty()) {
            SheetGroup(title = "Courses") {
                courses.forEachIndexed { idx, course ->
                    if (idx > 0) ListCardDivider(startIndent = 60.dp)
                    val selected = selectedOverride?.name == course.name
                    val sub = listOfNotNull(
                        course.room.takeIf { it.isNotBlank() },
                        course.teacher.takeIf { it.isNotBlank() }
                    ).joinToString(" · ").ifBlank { null }
                    ListPageRow(
                        title = course.name,
                        subtitle = sub,
                        leading = {
                            SettingsIcon(
                                icon = DshIcons.School,
                                identityKey = course.name
                            )
                        },
                        trailing = if (selected) {
                            {
                                Icon(
                                    imageVector = DshIcons.Check,
                                    contentDescription = "Selected",
                                    tint = scheme.primary
                                )
                            }
                        } else null,
                        showChevron = false,
                        onClick = { onSelect(course) }
                    )
                }
            }
        }
    }
}
