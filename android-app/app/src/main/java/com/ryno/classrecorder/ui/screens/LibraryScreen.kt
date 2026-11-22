package com.ryno.classrecorder.ui.screens

import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ryno.classrecorder.core.ExportProgress
import com.ryno.classrecorder.core.RecordingRecord
import com.ryno.classrecorder.ui.ExportFormat
import com.ryno.classrecorder.ui.ExportTarget
import com.ryno.classrecorder.ui.MainUiState
import com.ryno.classrecorder.ui.components.AppSheet
import com.ryno.classrecorder.ui.components.BackdropVeil
import com.ryno.classrecorder.ui.components.BindScrollUnderVeil
import com.ryno.classrecorder.ui.components.CapsuleChoiceChip
import com.ryno.classrecorder.ui.components.DshConfirmSheet
import com.ryno.classrecorder.ui.components.DshIcons
import com.ryno.classrecorder.ui.components.DshPrimaryButton
import com.ryno.classrecorder.ui.components.DshSecondaryButton
import com.ryno.classrecorder.ui.components.EmptyView
import com.ryno.classrecorder.ui.components.GlassIconButton
import com.ryno.classrecorder.ui.components.GlassSurface
import com.ryno.classrecorder.ui.components.ListCardDivider
import com.ryno.classrecorder.ui.components.ListPageCard
import com.ryno.classrecorder.ui.components.ListPageRow
import com.ryno.classrecorder.ui.components.LocalBackdropLayer
import com.ryno.classrecorder.ui.components.QuietTagPill
import com.ryno.classrecorder.ui.components.SearchCapsule
import com.ryno.classrecorder.ui.components.SettingsIcon
import com.ryno.classrecorder.ui.components.SheetGroup
import com.ryno.classrecorder.ui.components.rememberSafeSideInsets
import com.ryno.classrecorder.ui.theme.AppShape
import com.ryno.classrecorder.ui.theme.AppSpace
import com.ryno.classrecorder.ui.theme.ClassRecorderTheme
import com.ryno.classrecorder.ui.theme.DshMotion
import com.ryno.classrecorder.ui.theme.GlassTier
import com.ryno.classrecorder.ui.theme.StatusTone
import com.ryno.classrecorder.ui.theme.tabular
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Recordings library screen rebuilt in DSH Go's Mist Glass language.
 */
@Composable
fun LibraryScreen(
    ui: MainUiState,
    isActiveTab: Boolean = true,
    onToggleMcp: () -> Unit,
    onDeleteRecording: (String) -> Unit,
    onPrepareExport: (ExportTarget, ExportFormat) -> Unit = { _, _ -> },
    onExportToUri: (Uri, () -> Unit) -> Unit = { _, _ -> },
    onShowMessage: (String) -> Unit = {},
    onOpenPlayback: (RecordingRecord) -> Unit = {},
    topContentInset: Dp = 0.dp,
    onScrolledUnderChange: (Float) -> Unit = {}
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val listState = rememberLazyListState()

    var query by remember { mutableStateOf("") }
    var selectedCourse by remember { mutableStateOf<String?>(null) }
    var playingId by remember { mutableStateOf<String?>(null) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    var showExportSheet by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val exportProgress = ui.exportProgress
    val isExporting = exportProgress != null

    fun stopInlinePlayer() {
        runCatching {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) mp.stop()
                mp.release()
            }
        }
        mediaPlayer = null
        playingId = null
    }

    DisposableEffect(Unit) {
        onDispose { stopInlinePlayer() }
    }

    LaunchedEffect(isActiveTab) {
        if (!isActiveTab) {
            stopInlinePlayer()
        }
    }

    BackHandler(enabled = selectionMode) {
        selectionMode = false
        selectedIds = emptySet()
    }

    BindScrollUnderVeil(listState = listState, onScrolledUnderChange = onScrolledUnderChange)

    val onDocumentCreated: (Uri?) -> Unit = { uri ->
        if (uri != null) {
            onExportToUri(uri) {
                showExportSheet = false
                if (selectionMode) {
                    selectionMode = false
                    selectedIds = emptySet()
                }
            }
        }
    }

    val zipExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
        onResult = onDocumentCreated
    )

    val audioExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/mp4"),
        onResult = onDocumentCreated
    )

    fun launchExport(
        target: ExportTarget,
        matchedRecordings: List<RecordingRecord>,
        defaultZipName: String
    ) {
        if (matchedRecordings.size == 1) {
            val single = matchedRecordings.first()
            val rawName = File(single.filePath).name
            val safeName = UiFormatters.sanitizePathSegment(rawName, "recording.m4a")
            val suggestedName = if (safeName.endsWith(".m4a", ignoreCase = true)) safeName else "$safeName.m4a"
            onPrepareExport(ExportTarget.Selected(setOf(single.id)), ExportFormat.SINGLE_AUDIO)
            runCatching {
                audioExportLauncher.launch(suggestedName)
            }.onFailure {
                onShowMessage("No file picker available to save recording")
            }
        } else {
            onPrepareExport(target, ExportFormat.ZIP)
            runCatching {
                zipExportLauncher.launch(defaultZipName)
            }.onFailure {
                onShowMessage("No file picker available to save ZIP")
            }
        }
    }

    val courseCounts = remember(ui.recordings) {
        ui.recordings
            .groupingBy { it.courseName.ifBlank { "Uncategorized" } }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
    }

    val filtered = remember(ui.recordings, query, selectedCourse) {
        val q = query.trim().lowercase()
        ui.recordings
            .filter { rec ->
                val cName = rec.courseName.ifBlank { "Uncategorized" }
                val courseOk = selectedCourse == null || cName == selectedCourse
                val queryOk = q.isEmpty() ||
                    cName.lowercase().contains(q) ||
                    rec.notes.lowercase().contains(q) ||
                    rec.room.lowercase().contains(q) ||
                    rec.teacher.lowercase().contains(q) ||
                    rec.tags.any { it.lowercase().contains(q) }
                courseOk && queryOk
            }
            .sortedByDescending { it.startedAt }
    }

    val groupedByDay = remember(filtered) {
        filtered.groupBy { UiFormatters.toLocalDate(it.startedAt) }
            .entries
            .sortedByDescending { it.key }
    }

    fun toggleInlinePlayback(rec: RecordingRecord) {
        if (playingId == rec.id) {
            stopInlinePlayer()
            return
        }
        stopInlinePlayer()
        scope.launch {
            val exists = withContext(Dispatchers.IO) { File(rec.filePath).exists() }
            if (!exists) {
                onShowMessage("Audio file not found on disk")
                return@launch
            }
            runCatching {
                val mp = MediaPlayer().apply {
                    setDataSource(rec.filePath)
                    setOnPreparedListener { it.start() }
                    setOnCompletionListener {
                        playingId = null
                        runCatching { release() }
                        mediaPlayer = null
                    }
                    setOnErrorListener { _, _, _ ->
                        stopInlinePlayer()
                        onShowMessage("Unable to play recording")
                        true
                    }
                    prepareAsync()
                }
                mediaPlayer = mp
                playingId = rec.id
            }.onFailure {
                stopInlinePlayer()
                onShowMessage("Unable to play recording")
            }
        }
    }

    val showSelectionDock = selectionMode && selectedIds.isNotEmpty()
    val bottomDockHeight = if (showSelectionDock) navBottom + 96.dp else navBottom + 44.dp
    val reduced = ClassRecorderTheme.reducedMotion
    val contentBackdropLayer = rememberGraphicsLayer()
    val canScrollDown by remember { derivedStateOf { listState.canScrollForward } }
    val bottomVeilAlpha by animateFloatAsState(
        targetValue = if (showSelectionDock) 0.92f else if (canScrollDown) 0.94f else 0f,
        animationSpec = DshMotion.quickTween(reduced),
        label = "libraryBottomVeil"
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val (startInset, endInset) = rememberSafeSideInsets(maxWidth)

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (showSelectionDock) {
                        Modifier.drawWithContent {
                            contentBackdropLayer.record {
                                this@drawWithContent.drawContent()
                            }
                            drawLayer(contentBackdropLayer)
                        }
                    } else Modifier
                ),
            contentPadding = PaddingValues(
                start = AppSpace.gutter + startInset,
                end = AppSpace.gutter + endInset,
                top = topContentInset + 8.dp,
                bottom = bottomDockHeight
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "library_toolbar") {
                if (selectionMode) {
                    SelectionHeaderRow(
                        selectedCount = selectedIds.size,
                        allSelected = filtered.isNotEmpty() && selectedIds.size >= filtered.size,
                        onCancel = {
                            selectionMode = false
                            selectedIds = emptySet()
                        },
                        onToggleAll = {
                            selectedIds = if (selectedIds.size >= filtered.size) {
                                emptySet()
                            } else {
                                filtered.map { it.id }.toSet()
                            }
                        }
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SearchCapsule(
                            query = query,
                            onQueryChange = { query = it },
                            hint = "Search courses, notes, tags",
                            modifier = Modifier.weight(1f)
                        )
                        if (ui.recordings.isNotEmpty()) {
                            GlassIconButton(
                                icon = DshIcons.Checklist,
                                contentDescription = "Select recordings",
                                onClick = {
                                    selectionMode = true
                                    selectedIds = emptySet()
                                },
                                tier = GlassTier.Pill,
                                visualSize = 42.dp
                            )
                            GlassIconButton(
                                icon = DshIcons.FolderZip,
                                contentDescription = "Export recordings",
                                onClick = { showExportSheet = true },
                                tier = GlassTier.Pill,
                                visualSize = 42.dp
                            )
                        }
                    }
                }
            }

            if (courseCounts.size > 1) {
                item(key = "course_filters") {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 2.dp)
                    ) {
                        item(key = "__all__") {
                            CapsuleChoiceChip(
                                label = "All (${ui.recordings.size})",
                                selected = selectedCourse == null,
                                onClick = { selectedCourse = null }
                            )
                        }
                        items(
                            items = courseCounts,
                            key = { it.key }
                        ) { (courseName, count) ->
                            CapsuleChoiceChip(
                                label = "$courseName ($count)",
                                selected = selectedCourse == courseName,
                                courseKey = courseName,
                                onClick = {
                                    selectedCourse = if (selectedCourse == courseName) null else courseName
                                }
                            )
                        }
                    }
                }
            }

            when {
                ui.recordings.isEmpty() -> {
                    item(key = "empty_library") {
                        Spacer(Modifier.height(16.dp))
                        EmptyView(
                            icon = DshIcons.GraphicEq,
                            title = "No recordings yet",
                            message = "Captured lectures appear here grouped by day and tagged automatically from your schedule."
                        )
                    }
                }
                filtered.isEmpty() -> {
                    item(key = "empty_filtered") {
                        Spacer(Modifier.height(12.dp))
                        EmptyView(
                            icon = DshIcons.SearchOff,
                            title = "No matching recordings",
                            message = "Try a different search keyword or reset the course filter.",
                            actionLabel = "Clear filters",
                            onAction = {
                                query = ""
                                selectedCourse = null
                            },
                            compact = true
                        )
                    }
                }
                else -> {
                    items(
                        items = groupedByDay,
                        key = { it.key.toEpochDay() }
                    ) { (date, dayList) ->
                        ListPageCard(
                            title = UiFormatters.formatDayHeader(date),
                            trailingTitle = dayList.size.toString()
                        ) {
                            dayList.forEachIndexed { idx, rec ->
                                if (idx > 0) {
                                    ListCardDivider(startIndent = 64.dp)
                                }
                                RecordingLibraryRow(
                                    rec = rec,
                                    isPlaying = playingId == rec.id,
                                    selectionMode = selectionMode,
                                    isSelected = selectedIds.contains(rec.id),
                                    onRowClick = {
                                        if (selectionMode) {
                                            selectedIds = if (selectedIds.contains(rec.id)) {
                                                selectedIds - rec.id
                                            } else {
                                                selectedIds + rec.id
                                            }
                                        } else {
                                            stopInlinePlayer()
                                            onOpenPlayback(rec)
                                        }
                                    },
                                    onRowLongClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        selectionMode = true
                                        selectedIds = selectedIds + rec.id
                                    },
                                    onTogglePlay = { toggleInlinePlayback(rec) }
                                )
                            }
                        }
                    }
                }
            }
        }

        BackdropVeil(
            fromTop = false,
            solidHeight = if (showSelectionDock) navBottom + 20.dp else 0.dp,
            feather = if (showSelectionDock) 36.dp else navBottom + 4.dp,
            alpha = if (showSelectionDock) bottomVeilAlpha * 0.90f else bottomVeilAlpha * 0.65f,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        if (showSelectionDock) {
            CompositionLocalProvider(LocalBackdropLayer provides contentBackdropLayer) {
                SelectionDock(
                    selectedCount = selectedIds.size,
                    exportProgress = exportProgress,
                    onExportSelected = {
                        val ids = selectedIds
                        val matched = ui.recordings.filter { ids.contains(it.id) }
                        launchExport(
                            target = ExportTarget.Selected(ids),
                            matchedRecordings = matched,
                            defaultZipName = "recordings_selected_${ids.size}.zip"
                        )
                    },
                    onDeleteSelected = { showDeleteConfirm = true },
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
    }

    if (showExportSheet) {
        ExportRecordingsSheet(
            totalCount = ui.recordings.size,
            courseCounts = courseCounts.map { it.key to it.value },
            exportProgress = exportProgress,
            onExportAll = {
                launchExport(
                    target = ExportTarget.All,
                    matchedRecordings = ui.recordings,
                    defaultZipName = "class_recordings_all.zip"
                )
            },
            onExportCourse = { course ->
                val matched = ui.recordings.filter {
                    it.courseName.ifBlank { "Uncategorized" } == course
                }
                launchExport(
                    target = ExportTarget.Course(course),
                    matchedRecordings = matched,
                    defaultZipName = "${UiFormatters.sanitizePathSegment(course)}_recordings.zip"
                )
            },
            onDismiss = { showExportSheet = false }
        )
    }

    if (showDeleteConfirm) {
        DshConfirmSheet(
            title = "Delete ${selectedIds.size} ${if (selectedIds.size == 1) "recording" else "recordings"}?",
            message = "Deleted lecture audio files are removed permanently from device storage.",
            confirmLabel = "Delete",
            danger = true,
            onConfirm = {
                val toDelete = selectedIds.toList()
                showDeleteConfirm = false
                selectionMode = false
                selectedIds = emptySet()
                toDelete.forEach { id ->
                    if (playingId == id) stopInlinePlayer()
                    onDeleteRecording(id)
                }
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }
}

@Composable
private fun SelectionHeaderRow(
    selectedCount: Int,
    allSelected: Boolean,
    onCancel: () -> Unit,
    onToggleAll: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlassIconButton(
            icon = DshIcons.Close,
            contentDescription = "Exit selection",
            onClick = onCancel,
            tier = GlassTier.Pill,
            visualSize = 40.dp
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "$selectedCount selected",
            style = MaterialTheme.typography.titleMedium.tabular(),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        CapsuleChoiceChip(
            label = if (allSelected) "Deselect all" else "Select all",
            selected = allSelected,
            onClick = onToggleAll
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordingLibraryRow(
    rec: RecordingRecord,
    isPlaying: Boolean,
    selectionMode: Boolean,
    isSelected: Boolean,
    onRowClick: () -> Unit,
    onRowLongClick: () -> Unit,
    onTogglePlay: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val dsh = ClassRecorderTheme.dshColors
    val courseTitle = rec.courseName.ifBlank { "Uncategorized" }
    val (identityBg, identityFg) = dsh.identityPair(courseTitle)

    val metaLine = buildString {
        append(UiFormatters.formatClockTime(rec.startedAt))
        append(" · ")
        append(UiFormatters.formatDuration(rec.durationMs))
        if (rec.room.isNotBlank()) {
            append(" · ")
            append(rec.room)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 68.dp)
            .combinedClickable(
                onClick = onRowClick,
                onLongClick = onRowLongClick
            )
            .padding(start = 16.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when {
            selectionMode -> {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) scheme.primary else scheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isSelected) DshIcons.Check else DshIcons.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (isSelected) scheme.onPrimary else dsh.textTertiary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            isPlaying -> {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(dsh.dangerContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = DshIcons.GraphicEq,
                        contentDescription = null,
                        tint = dsh.onDangerContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            else -> {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(identityBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = DshIcons.GraphicEq,
                        contentDescription = null,
                        tint = identityFg,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = courseTitle,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                val firstTag = rec.tags.firstOrNull()
                if (!firstTag.isNullOrBlank()) {
                    Spacer(Modifier.width(6.dp))
                    QuietTagPill(text = if (firstTag.startsWith("#")) firstTag else "#$firstTag")
                }
            }

            Spacer(Modifier.height(2.dp))
            Text(
                text = metaLine,
                style = MaterialTheme.typography.bodySmall.tabular(),
                color = dsh.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (rec.notes.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = rec.notes.lineSequence().firstOrNull().orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (!selectionMode) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = if (isPlaying) "Stop preview" else "Preview audio",
                        onClick = onTogglePlay
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPlaying) DshIcons.Stop else DshIcons.PlayArrow,
                    contentDescription = if (isPlaying) "Stop preview" else "Preview audio",
                    tint = if (isPlaying) dsh.danger else scheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun SelectionDock(
    selectedCount: Int,
    exportProgress: ExportProgress?,
    onExportSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isExporting = exportProgress != null
    val isSingle = selectedCount == 1
    val exportLabel = when {
        exportProgress == null -> if (isSingle) "Export (.m4a)" else "Export (.zip)"
        exportProgress.totalFiles > 1 ->
            "Exporting ${exportProgress.completedFiles}/${exportProgress.totalFiles}…"
        else -> "Exporting…"
    }
    GlassSurface(
        modifier = modifier.fillMaxWidth(),
        tier = GlassTier.Chrome,
        shape = AppShape.radiusXl,
        contentPadding = PaddingValues(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DshSecondaryButton(
                label = exportLabel,
                icon = if (isSingle) DshIcons.Save else DshIcons.FolderZip,
                enabled = !isExporting,
                onClick = onExportSelected,
                modifier = Modifier.weight(1f)
            )
            DshPrimaryButton(
                label = "Delete ($selectedCount)",
                icon = DshIcons.DeleteOutline,
                danger = true,
                enabled = !isExporting,
                onClick = onDeleteSelected,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ExportRecordingsSheet(
    totalCount: Int,
    courseCounts: List<Pair<String, Int>>,
    exportProgress: ExportProgress?,
    onExportAll: () -> Unit,
    onExportCourse: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val dsh = ClassRecorderTheme.dshColors
    val scheme = MaterialTheme.colorScheme
    val isExporting = exportProgress != null
    val primaryLabel = when {
        exportProgress == null -> if (totalCount == 1) "Export recording (.m4a)" else "Export all ($totalCount · .zip)"
        exportProgress.totalFiles > 1 ->
            "Exporting ${exportProgress.completedFiles}/${exportProgress.totalFiles}…"
        else -> "Exporting…"
    }

    AppSheet(
        onDismiss = onDismiss,
        title = "Export recordings"
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpace.sheetGutter),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Exports a single lecture directly as .m4a, or bundles multiple files into a .zip organized by course folder with metadata.json.",
                    style = MaterialTheme.typography.bodySmall,
                    color = dsh.textTertiary
                )
                if (exportProgress != null && exportProgress.totalFiles > 0) {
                    LinearProgressIndicator(
                        progress = {
                            (exportProgress.completedFiles.toFloat() / exportProgress.totalFiles.toFloat())
                                .coerceIn(0f, 1f)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape),
                        color = scheme.primary,
                        trackColor = scheme.surfaceContainerHighest
                    )
                }
                DshPrimaryButton(
                    label = primaryLabel,
                    icon = if (totalCount == 1) DshIcons.Save else DshIcons.FolderZip,
                    enabled = !isExporting && totalCount > 0,
                    onClick = onExportAll,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (courseCounts.size > 1) {
                Spacer(Modifier.height(8.dp))
                SheetGroup(title = "By course") {
                    courseCounts.forEachIndexed { idx, (course, count) ->
                        if (idx > 0) ListCardDivider(startIndent = 60.dp)
                        ListPageRow(
                            title = course,
                            meta = if (count == 1) "1 file · .m4a" else "$count files · .zip",
                            leading = {
                                SettingsIcon(
                                    icon = if (count == 1) DshIcons.Save else DshIcons.FolderZip,
                                    identityKey = course
                                )
                            },
                            onClick = if (!isExporting) {
                                { onExportCourse(course) }
                            } else null
                        )
                    }
                }
            }
        }
    }
}
