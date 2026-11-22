package com.ryno.classrecorder.ui.screens

import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.ryno.classrecorder.core.RecordingRecord
import com.ryno.classrecorder.ui.components.AppPage
import com.ryno.classrecorder.ui.components.AppSheet
import com.ryno.classrecorder.ui.components.CodeWell
import com.ryno.classrecorder.ui.components.DshConfirmSheet
import com.ryno.classrecorder.ui.components.DshIcons
import com.ryno.classrecorder.ui.components.DshPrimaryButton
import com.ryno.classrecorder.ui.components.DshSecondaryButton
import com.ryno.classrecorder.ui.components.DshTextField
import com.ryno.classrecorder.ui.components.GlassIconButton
import com.ryno.classrecorder.ui.components.GlassSurface
import com.ryno.classrecorder.ui.components.ListPageCard
import com.ryno.classrecorder.ui.components.OverviewGlyph
import com.ryno.classrecorder.ui.components.PageHero
import com.ryno.classrecorder.ui.components.PillSegment
import com.ryno.classrecorder.ui.components.SegmentedPill
import com.ryno.classrecorder.ui.theme.AppShape
import com.ryno.classrecorder.ui.theme.AppSpace
import com.ryno.classrecorder.ui.theme.ClassRecorderTheme
import com.ryno.classrecorder.ui.theme.GlassTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

private val SPEED_SEGMENTS = listOf(
    PillSegment(0.5f, "0.5×"),
    PillSegment(1.0f, "1×"),
    PillSegment(1.25f, "1.25×"),
    PillSegment(1.5f, "1.5×"),
    PillSegment(2.0f, "2×")
)

/**
 * Full-screen lecture playback & note editor built on DSH Go's [AppPage] + [PageHero].
 */
@Composable
fun PlaybackScreen(
    recording: RecordingRecord,
    onDismiss: () -> Unit,
    onUpdateNotes: (String, String, List<String>) -> Unit,
    onDeleteRecording: (String) -> Unit,
    onExportRecording: (RecordingRecord, Uri) -> Unit = { _, _ -> },
    isExporting: Boolean = false,
    onShowMessage: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val dsh = ClassRecorderTheme.dshColors
    val mono = ClassRecorderTheme.mono

    var isPlaying by remember(recording.id) { mutableStateOf(false) }
    var currentPosMs by remember(recording.id) { mutableLongStateOf(0L) }
    var durationMs by remember(recording.id) { mutableLongStateOf(recording.durationMs.coerceAtLeast(1L)) }
    var speed by remember(recording.id) { mutableFloatStateOf(1.0f) }
    var showNotesSheet by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var decodedPeaks by remember(recording.id) { mutableStateOf<List<Float>>(emptyList()) }
    var fileExists by remember(recording.filePath) { mutableStateOf(true) }
    var fileSizeLabel by remember(recording.filePath) { mutableStateOf("…") }
    var lastScrubSeekUptimeMs by remember { mutableLongStateOf(0L) }

    val fileName = remember(recording.filePath) { File(recording.filePath).name }

    val exportAudioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/mp4")
    ) { uri: Uri? ->
        if (uri != null) {
            onExportRecording(recording, uri)
        }
    }

    LaunchedEffect(recording.id, recording.filePath) {
        val (exists, sizeStr) = withContext(Dispatchers.IO) {
            val f = File(recording.filePath)
            val len = f.length()
            val ok = f.exists() && len > 0L
            ok to (if (ok) UiFormatters.formatFileSize(len) else "Unavailable")
        }
        fileExists = exists
        fileSizeLabel = sizeStr
        decodedPeaks = AudioWaveformDecoder.extractPeaks(recording.filePath, bucketCount = 72)
    }

    var mediaPlayerRef by remember(recording.id) { mutableStateOf<MediaPlayer?>(null) }
    var isPrepared by remember(recording.id) { mutableStateOf(false) }

    DisposableEffect(recording.id, recording.filePath) {
        val mp = MediaPlayer()
        mediaPlayerRef = mp
        isPrepared = false
        runCatching {
            mp.setDataSource(recording.filePath)
            mp.setOnPreparedListener { prepared ->
                durationMs = prepared.duration.toLong().coerceAtLeast(1L)
                isPrepared = true
            }
            mp.setOnCompletionListener {
                isPlaying = false
                currentPosMs = durationMs
            }
            mp.prepareAsync()
        }
        onDispose {
            isPrepared = false
            isPlaying = false
            mediaPlayerRef = null
            runCatching { if (mp.isPlaying) mp.stop() }
            runCatching { mp.release() }
        }
    }

    LaunchedEffect(recording.id, isPlaying) {
        while (isPlaying) {
            val mp = mediaPlayerRef
            if (mp != null && isPrepared) {
                runCatching {
                    currentPosMs = mp.currentPosition.toLong().coerceIn(0L, durationMs)
                }
            }
            delay(32L)
        }
    }

    fun togglePlayPause() {
        val mp = mediaPlayerRef ?: return
        if (!isPrepared) return
        runCatching {
            if (mp.isPlaying) {
                mp.pause()
                isPlaying = false
            } else {
                if (currentPosMs >= durationMs - 150L) {
                    mp.seekTo(0)
                    currentPosMs = 0L
                }
                mp.playbackParams = PlaybackParams().setSpeed(speed)
                mp.start()
                isPlaying = true
            }
        }
    }

    fun seekTo(targetMs: Long, force: Boolean = true) {
        val clamped = targetMs.coerceIn(0L, durationMs)
        currentPosMs = clamped
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastScrubSeekUptimeMs < 80L) return
        lastScrubSeekUptimeMs = now
        val mp = mediaPlayerRef ?: return
        if (isPrepared) {
            runCatching { mp.seekTo(clamped.toInt()) }
        }
    }

    fun applySpeed(next: Float) {
        speed = next
        val mp = mediaPlayerRef ?: return
        if (isPrepared && mp.isPlaying) {
            runCatching {
                mp.playbackParams = PlaybackParams().setSpeed(next)
            }
        }
    }

    val courseTitle = recording.courseName.ifBlank { "Uncategorized" }
    val progress = (currentPosMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    val currentDisplaySeconds = currentPosMs / 1000L

    val heroSubtitle = remember(recording) {
        listOfNotNull(
            UiFormatters.formatTimestamp(recording.startedAt).takeIf { it.isNotBlank() },
            recording.slot.takeIf { it.isNotBlank() }?.let { "P$it" },
            recording.room.takeIf { it.isNotBlank() },
            recording.teacher.takeIf { it.isNotBlank() }
        ).joinToString(" · ")
    }

    val heroStats = remember(durationMs, fileSizeLabel) {
        listOf(
            UiFormatters.formatDuration(durationMs) to "Duration",
            "128 kbps" to "AAC audio",
            fileSizeLabel to "File size"
        )
    }

    AppPage(
        title = courseTitle,
        onBack = onDismiss,
        actions = {
            GlassIconButton(
                icon = DshIcons.EditNote,
                contentDescription = "Edit notes and tags",
                onClick = { showNotesSheet = true }
            )
        },
        hero = {
            PageHero(
                title = courseTitle,
                subtitle = heroSubtitle,
                tags = recording.tags,
                stats = heroStats,
                leading = {
                    OverviewGlyph(
                        icon = DshIcons.GraphicEq,
                        identityKey = courseTitle
                    )
                }
            )
        }
    ) {
        item(key = "waveform_card") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpace.gutter),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                GlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    tier = GlassTier.Card,
                    shape = AppShape.radiusLg,
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        ScrubWaveformCanvas(
                            peaks = decodedPeaks,
                            progress = progress,
                            onScrub = { frac ->
                                seekTo((frac * durationMs.toFloat()).toLong(), force = false)
                            },
                            onScrubFinished = {
                                seekTo(currentPosMs, force = true)
                            },
                            playedColor = scheme.primary,
                            unplayedColor = dsh.textTertiary,
                            playheadColor = scheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(152.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        // Pure GPU render-node scaleX avoids 31 fps layout passes
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(CircleShape)
                                .background(dsh.textTertiary.copy(alpha = 0.60f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        scaleX = progress
                                        transformOrigin = TransformOrigin(0f, 0.5f)
                                    }
                                    .background(scheme.primary)
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = remember(currentDisplaySeconds) {
                                    UiFormatters.formatDuration(currentDisplaySeconds * 1000L)
                                },
                                style = mono.seekLabel,
                                color = scheme.onSurface
                            )
                            Text(
                                text = remember(durationMs) { UiFormatters.formatDuration(durationMs) },
                                style = mono.seekLabel,
                                color = dsh.textTertiary
                            )
                        }
                    }
                }

                // Transport controls: -5s · Play/Pause · +10s
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    GlassSurface(
                        modifier = Modifier.size(52.dp),
                        tier = GlassTier.Card,
                        circle = true,
                        enabled = fileExists,
                        onClick = { seekTo(currentPosMs - 5_000L) }
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = DshIcons.Replay5,
                                contentDescription = "Rewind 5 seconds",
                                tint = if (fileExists) scheme.onSurface else dsh.textDisabled,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    DshPrimaryButton(
                        label = if (isPlaying) "Pause" else "Play",
                        icon = if (isPlaying) DshIcons.Pause else DshIcons.PlayArrow,
                        enabled = fileExists,
                        onClick = ::togglePlayPause,
                        minHeight = 56.dp,
                        modifier = Modifier.weight(1f)
                    )

                    GlassSurface(
                        modifier = Modifier.size(52.dp),
                        tier = GlassTier.Card,
                        circle = true,
                        enabled = fileExists,
                        onClick = { seekTo(currentPosMs + 10_000L) }
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = DshIcons.Forward10,
                                contentDescription = "Forward 10 seconds",
                                tint = if (fileExists) scheme.onSurface else dsh.textDisabled,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                // Playback speed (floats directly without a nested outer glass card)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Playback speed",
                        style = MaterialTheme.typography.labelLarge,
                        color = dsh.textTertiary,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    SegmentedPill(
                        segments = SPEED_SEGMENTS,
                        selected = speed,
                        onSelect = ::applySpeed,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Lecture notes
                ListPageCard(
                    title = "Lecture notes",
                    contentPadding = PaddingValues(0.dp)
                ) {
                    GlassSurface(
                        modifier = Modifier.fillMaxWidth(),
                        tier = GlassTier.Pill,
                        shape = AppShape.radiusLg,
                        onClick = { showNotesSheet = true },
                        contentPadding = PaddingValues(16.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            if (recording.notes.isBlank()) {
                                Text(
                                    text = "Tap to add lecture notes, summary points, or #tags…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = dsh.textTertiary
                                )
                            } else {
                                Text(
                                    text = recording.notes,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = scheme.onSurface
                                )
                            }
                        }
                    }
                }

                // File identity & delete
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CodeWell(
                        label = "Audio file",
                        text = fileName,
                        onCopy = {
                            UiFormatters.copyToClipboard(context, "Filename", fileName)
                            onShowMessage("Copied filename")
                        }
                    )

                    DshSecondaryButton(
                        label = if (isExporting) "Exporting…" else "Export audio (.m4a)",
                        icon = DshIcons.Save,
                        enabled = fileExists && !isExporting,
                        onClick = {
                            val safe = UiFormatters.sanitizePathSegment(fileName, "recording.m4a")
                            val suggested = if (safe.endsWith(".m4a", ignoreCase = true)) safe else "$safe.m4a"
                            runCatching {
                                exportAudioLauncher.launch(suggested)
                            }.onFailure {
                                onShowMessage("No file picker available to save recording")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    DshSecondaryButton(
                        label = "Delete recording",
                        icon = DshIcons.DeleteOutline,
                        danger = true,
                        onClick = { showDeleteDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    if (showNotesSheet) {
        NotesEditorSheet(
            initialNotes = recording.notes,
            initialTags = recording.tags,
            onSave = { notes, tags ->
                onUpdateNotes(recording.id, notes, tags)
                showNotesSheet = false
            },
            onDismiss = { showNotesSheet = false }
        )
    }

    if (showDeleteDialog) {
        DshConfirmSheet(
            title = "Delete recording?",
            message = "“$fileName” will be permanently deleted from device storage.",
            confirmLabel = "Delete",
            danger = true,
            onConfirm = {
                showDeleteDialog = false
                runCatching { mediaPlayerRef?.let { if (it.isPlaying) it.stop() } }
                onDeleteRecording(recording.id)
                onDismiss()
            },
            onDismiss = { showDeleteDialog = false }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotesEditorSheet(
    initialNotes: String,
    initialTags: List<String>,
    onSave: (String, List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var notes by remember(initialNotes) { mutableStateOf(initialNotes) }
    var tagInput by remember { mutableStateOf("") }
    val tags = remember(initialTags) {
        mutableStateListOf<String>().apply { addAll(initialTags) }
    }
    val scheme = MaterialTheme.colorScheme
    val surfaces = ClassRecorderTheme.surfaces

    fun addTag() {
        val cleaned = tagInput.trim().removePrefix("#").trim()
        if (cleaned.isNotEmpty() && !tags.contains(cleaned)) {
            tags.add(cleaned)
        }
        tagInput = ""
    }

    AppSheet(
        onDismiss = onDismiss,
        title = "Notes & tags"
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpace.sheetGutter),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            DshTextField(
                value = notes,
                onValueChange = { notes = it },
                label = "Lecture notes",
                placeholder = "Key takeaways, exam topics, timestamps…",
                singleLine = false,
                minLines = 4,
                maxLines = 8
            )

            DshTextField(
                value = tagInput,
                onValueChange = { tagInput = it },
                label = "Tags",
                placeholder = "Add a tag (e.g. midterm, ch4)",
                imeAction = ImeAction.Done,
                onImeAction = ::addTag,
                trailing = {
                    GlassIconButton(
                        icon = DshIcons.Add,
                        contentDescription = "Add tag",
                        onClick = ::addTag,
                        tier = GlassTier.Pill,
                        visualSize = 36.dp,
                        iconSize = 18.dp
                    )
                }
            )

            if (tags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    tags.forEach { tag ->
                        Box(
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            GlassSurface(
                                tier = GlassTier.Pill,
                                stadium = true,
                                onClick = { tags.remove(tag) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "#$tag",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (surfaces.darkTheme) scheme.onSurface else scheme.onSurfaceVariant
                                    )
                                    Icon(
                                        imageVector = DshIcons.Close,
                                        contentDescription = "Remove $tag",
                                        modifier = Modifier.size(14.dp),
                                        tint = scheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            DshPrimaryButton(
                label = "Save notes",
                onClick = { onSave(notes.trim(), tags.toList()) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
