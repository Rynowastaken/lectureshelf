package com.ryno.classrecorder.ui.screens

import android.content.Intent
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ryno.classrecorder.core.AppDefaults
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.ryno.classrecorder.ui.AppearancePreferences
import com.ryno.classrecorder.ui.CustomBackground
import com.ryno.classrecorder.ui.CustomBackgroundRevision
import com.ryno.classrecorder.ui.DshThemeMode
import com.ryno.classrecorder.ui.MainUiState
import com.ryno.classrecorder.ui.components.AppPage
import com.ryno.classrecorder.ui.components.CapsuleChoiceChip
import com.ryno.classrecorder.ui.components.CodeWell
import com.ryno.classrecorder.ui.components.DshIcons
import com.ryno.classrecorder.ui.components.DshPrimaryButton
import com.ryno.classrecorder.ui.components.DshSecondaryButton
import com.ryno.classrecorder.ui.components.DshTextField
import com.ryno.classrecorder.ui.components.ListCardDivider
import com.ryno.classrecorder.ui.components.ListPageCard
import com.ryno.classrecorder.ui.components.ListPageRow
import com.ryno.classrecorder.ui.components.OverviewCard
import com.ryno.classrecorder.ui.components.OverviewFooterRow
import com.ryno.classrecorder.ui.components.OverviewGlyph
import com.ryno.classrecorder.ui.components.SettingsIcon
import com.ryno.classrecorder.ui.components.SettingsSwitchTile
import com.ryno.classrecorder.ui.components.dshSwitchColors
import com.ryno.classrecorder.ui.theme.AppAccent
import com.ryno.classrecorder.ui.theme.AppShape
import com.ryno.classrecorder.ui.theme.AppSpace
import com.ryno.classrecorder.ui.theme.ClassRecorderTheme
import com.ryno.classrecorder.ui.theme.DshMotion
import com.ryno.classrecorder.ui.theme.DshPalette
import com.ryno.classrecorder.ui.theme.DshSurfaces
import com.ryno.classrecorder.ui.theme.StatusTone
import com.ryno.classrecorder.ui.theme.resolveAppAccent

private val DATE_FORMATS = listOf(
    "yyyy-MM-dd",
    "yyyyMMdd",
    "MM-dd",
    "yyyy.MM.dd",
    "dd-MM-yyyy"
)
private val PLACEHOLDER_TOKENS = listOf(
    "{class}",
    "{date}",
    "{lesson number}",
    "{room}",
    "{teacher}",
    "{time}",
    "{slot}"
)

/**
 * Full-screen DSH Go Settings hub built on [AppPage] + [OverviewCard] + [ListPageCard].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    ui: MainUiState,
    onDismiss: () -> Unit,
    onSelectThemeMode: (DshThemeMode) -> Unit,
    onSelectAccentId: (String) -> Unit,
    onSaveFilenameTemplate: (String, String) -> Unit,
    onSyncSchedule: (String) -> Unit,
    onToggleMcp: () -> Unit,
    onCheckUpdates: () -> Unit = {},
    onInstallUpdate: ((Intent) -> Unit) -> Unit = {},
    onShowMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val surfaces = ClassRecorderTheme.surfaces
    val reduced = ClassRecorderTheme.reducedMotion

    var showMcpEndpoints by rememberSaveable { mutableStateOf(false) }
    var scheduleUrl by rememberSaveable(ui.scheduleSourceInput) { mutableStateOf(ui.scheduleSourceInput) }
    var templateInput by rememberSaveable(ui.filenameTemplate) { mutableStateOf(ui.filenameTemplate) }
    var dateFormatInput by rememberSaveable(ui.dateFormat) { mutableStateOf(ui.dateFormat) }
    var lastPresetId by rememberSaveable {
        mutableStateOf(
            if (ui.accentId != AppAccent.WALLPAPER_ID) ui.accentId
            else AppearancePreferences.loadLastPresetId(context)
        )
    }

    val activeAccent = resolveAppAccent(ui.accentId)
    val lanUrl = "http://${ui.lanIp}:${ui.mcpStatus.port}/mcp"
    val sampleCourse = ui.schedule?.slots
        ?.flatMap { it.days }
        ?.firstOrNull { it != null && it.name.isNotBlank() }

    // -- Custom background ------------------------------------------------
    var customBackgroundPath by remember {
        mutableStateOf(AppearancePreferences.loadBackgroundPath(context))
    }
    // Live slider value, committed to prefs on release (see onValueChangeFinished).
    var blurStrength by remember {
        mutableFloatStateOf(AppearancePreferences.loadBackgroundBlur(context))
    }
    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Copy immediately: the picker's read grant is transient, and decoding
        // a 48 MP photo on the UI thread later would stall the frame.
        val saved = CustomBackground.save(context, uri)
        if (saved != null) {
            AppearancePreferences.saveBackgroundPath(context, saved)
            CustomBackgroundRevision.bump()
            customBackgroundPath = saved
        }
    }

    val installPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (context.packageManager.canRequestPackageInstalls()) {
            onInstallUpdate { }
        }
    }

    val livePreviewName = remember(templateInput, dateFormatInput, sampleCourse) {
        UiFormatters.previewFilename(
            template = templateInput,
            dateFormat = dateFormatInput,
            courseName = sampleCourse?.name ?: "大學之道",
            room = sampleCourse?.room?.ifBlank { "3309" } ?: "3309",
            teacher = sampleCourse?.teacher?.ifBlank { "何昕家" } ?: "何昕家"
        )
    }

    AppPage(
        title = "Settings",
        onBack = onDismiss
    ) {
        item(key = "settings_body") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpace.gutter),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // 1. MCP Server Hub Card
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OverviewCard(
                        leading = {
                            OverviewGlyph(
                                icon = DshIcons.Hub,
                                tintSlot = 0
                            )
                        },
                        title = "On-device MCP server",
                        subtitle = if (ui.mcpStatus.running) {
                            "Listening on port ${ui.mcpStatus.port}"
                        } else {
                            "Stopped · Toggle to expose agent tools"
                        },
                        trailing = {
                            Switch(
                                checked = ui.mcpStatus.running,
                                onCheckedChange = { onToggleMcp() },
                                colors = dshSwitchColors()
                            )
                        },
                        footer = {
                            OverviewFooterRow(
                                tone = if (ui.mcpStatus.running) StatusTone.Ok else StatusTone.Idle,
                                // The URL is the useful part and must survive
                                // truncation, so it goes first and the hint
                                // that used to follow it is gone - the trailing
                                // chevron already signals "tap for more".
                                text = if (ui.mcpStatus.running) {
                                    lanUrl
                                } else {
                                    "Stopped · tap to see connection commands"
                                }
                            )
                        },
                        onFooterClick = { showMcpEndpoints = !showMcpEndpoints }
                    )

                    AnimatedVisibility(
                        visible = showMcpEndpoints,
                        enter = expandVertically(DshMotion.collapseTween(reduced)) + fadeIn(DshMotion.quickTween(reduced)),
                        exit = shrinkVertically(DshMotion.collapseTween(reduced)) + fadeOut(DshMotion.quickTween(reduced))
                    ) {
                        McpEndpointsBlock(
                            lanIp = ui.lanIp,
                            port = ui.mcpStatus.port,
                            onCopied = onShowMessage
                        )
                    }
                }

                // 2. Appearance Section
                ListPageCard(
                    title = "Appearance",
                    footer = "Colors are contrast-guarded in OKLCH perceptual space across Morning Mist and Slate Night."
                ) {
                    ThemeModePickerRow(
                        selectedMode = ui.themeMode,
                        accent = activeAccent,
                        onSelectMode = onSelectThemeMode
                    )

                    ListCardDivider()

                    PalettePickerRow(
                        selectedAccentId = ui.accentId,
                        darkTheme = surfaces.darkTheme,
                        onSelectPreset = { id ->
                            lastPresetId = id
                            onSelectAccentId(id)
                        }
                    )

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        ListCardDivider()
                        val wallpaperAccent = resolveAppAccent(AppAccent.WALLPAPER_ID)
                        val wallpaperDot = wallpaperAccent.seedFor(surfaces.darkTheme)
                        val followingWallpaper = ui.accentId == AppAccent.WALLPAPER_ID
                        SettingsSwitchTile(
                            title = "Follow wallpaper",
                            subtitle = "Derive guarded Mist Glass palette from Android system color",
                            checked = followingWallpaper,
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    if (ui.accentId != AppAccent.WALLPAPER_ID) {
                                        lastPresetId = ui.accentId
                                    }
                                    onSelectAccentId(AppAccent.WALLPAPER_ID)
                                } else {
                                    onSelectAccentId(lastPresetId)
                                }
                            },
                            leading = {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(wallpaperDot.copy(alpha = 0.22f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clip(CircleShape)
                                            .background(wallpaperDot)
                                            .border(0.5.dp, scheme.onSurface.copy(alpha = 0.12f), CircleShape)
                                    )
                                }
                            }
                        )
                    }
                }

                // 2b. Background Section
                BackgroundPickerCard(
                    hasCustomBackground = customBackgroundPath != null,
                    blurStrength = blurStrength,
                    onBlurChange = { blurStrength = it },
                    onBlurChangeFinished = {
                        AppearancePreferences.saveBackgroundBlur(context, blurStrength)
                        CustomBackgroundRevision.bump()
                    },
                    onPick = { pickerLauncher.launch(arrayOf("image/*")) },
                    onClear = {
                        CustomBackground.clear(context)
                        CustomBackgroundRevision.bump()
                        customBackgroundPath = null
                    }
                )

                // 3. Class Schedule Section
                ListPageCard(
                    title = "Class schedule",
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    ListPageRow(
                        title = ui.schedule?.schoolName?.ifBlank { "No timetable synced" } ?: "No timetable synced",
                        subtitle = ui.schedule?.let { "${it.slots.size} periods configured" }
                            ?: "Sync a uni-campus or NUTC Portal timetable",
                        leading = {
                            SettingsIcon(
                                icon = DshIcons.CalendarMonth,
                                tintSlot = 5
                            )
                        },
                        showChevron = false
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        DshTextField(
                            value = scheduleUrl,
                            onValueChange = { scheduleUrl = it },
                            label = "Portal address or share link",
                            placeholder = "https://portal.example.com or share code",
                            mono = true,
                            imeAction = ImeAction.Go,
                            onImeAction = {
                                if (scheduleUrl.isNotBlank() && !ui.isSyncing) onSyncSchedule(scheduleUrl.trim())
                            }
                        )
                        DshPrimaryButton(
                            label = if (ui.isSyncing) "Syncing…" else "Sync schedule",
                            enabled = scheduleUrl.isNotBlank() && !ui.isSyncing,
                            onClick = { onSyncSchedule(scheduleUrl.trim()) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // 4. File Naming Section
                ListPageCard(
                    title = "File naming",
                    footer = "Applied automatically when new .m4a lecture recordings are created.",
                    contentPadding = PaddingValues(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        DshTextField(
                            value = templateInput,
                            onValueChange = { templateInput = it },
                            label = "Filename template",
                            placeholder = AppDefaults.FILENAME_TEMPLATE,
                            mono = true
                        )

                        CodeWell(
                            label = "Live preview",
                            text = livePreviewName
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Date format",
                                style = MaterialTheme.typography.labelMedium,
                                color = ClassRecorderTheme.dshColors.textTertiary,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                DATE_FORMATS.forEach { fmt ->
                                    CapsuleChoiceChip(
                                        label = fmt,
                                        selected = dateFormatInput == fmt,
                                        onClick = { dateFormatInput = fmt }
                                    )
                                }
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Insert token",
                                style = MaterialTheme.typography.labelMedium,
                                color = ClassRecorderTheme.dshColors.textTertiary,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                PLACEHOLDER_TOKENS.forEach { token ->
                                    CapsuleChoiceChip(
                                        label = token,
                                        selected = false,
                                        onClick = {
                                            templateInput = if (templateInput.isBlank()) {
                                                token
                                            } else {
                                                "${templateInput}_$token"
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            DshSecondaryButton(
                                label = "Reset",
                                icon = DshIcons.RestartAlt,
                                onClick = {
                                    templateInput = AppDefaults.FILENAME_TEMPLATE
                                    dateFormatInput = AppDefaults.DATE_FORMAT
                                    onSaveFilenameTemplate(AppDefaults.FILENAME_TEMPLATE, AppDefaults.DATE_FORMAT)
                                },
                                modifier = Modifier.weight(1f)
                            )
                            DshPrimaryButton(
                                label = "Save",
                                icon = DshIcons.Save,
                                onClick = {
                                    onSaveFilenameTemplate(
                                        templateInput.trim().ifBlank { AppDefaults.FILENAME_TEMPLATE },
                                        dateFormatInput.trim().ifBlank { AppDefaults.DATE_FORMAT }
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // 5. App updates
                val upd = ui.updateState
                val available = upd.availableUpdate
                val dlProgress = upd.downloadProgress
                val updateSubtitle = when {
                    dlProgress != null -> "Downloading ${available?.versionTag.orEmpty()}… $dlProgress%"
                    available != null -> buildString {
                        append("Update ${available.versionTag} available")
                        if (available.apkSizeBytes > 0L) {
                            append(" · ")
                            append(UiFormatters.formatFileSize(available.apkSizeBytes))
                        }
                    }
                    upd.isChecking -> "Checking GitHub Releases…"
                    upd.upToDateChecked -> "Up to date"
                    else -> "Check GitHub Releases for newer builds"
                }

                ListPageCard(
                    title = "App updates",
                    footer = "Downloads official signed APKs directly from GitHub Releases."
                ) {
                    ListPageRow(
                        title = "LectureShelf v${upd.currentVersion.removePrefix("v")}",
                        subtitle = updateSubtitle,
                        leading = {
                            SettingsIcon(
                                icon = if (available != null) DshIcons.AutoAwesome else DshIcons.Refresh,
                                tintSlot = if (available != null) 0 else 2
                            )
                        }
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (available != null && available.releaseNotes.isNotBlank()) {
                            Text(
                                text = available.releaseNotes
                                    .lineSequence()
                                    .map { it.trim().removePrefix("#").removePrefix("##").removePrefix("###").trim() }
                                    .filter { it.isNotBlank() }
                                    .take(4)
                                    .joinToString("\n"),
                                style = MaterialTheme.typography.bodySmall,
                                color = ClassRecorderTheme.dshColors.textTertiary,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (dlProgress != null) {
                            LinearProgressIndicator(
                                progress = { (dlProgress / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(CircleShape),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                            )
                        }
                        if (available != null) {
                            val readyToInstall = !upd.downloadedApkPath.isNullOrBlank()
                            DshPrimaryButton(
                                label = when {
                                    dlProgress != null -> "Downloading $dlProgress%…"
                                    readyToInstall -> "Install ${available.versionTag}"
                                    else -> "Download & install ${available.versionTag}"
                                },
                                icon = DshIcons.Save,
                                enabled = dlProgress == null,
                                onClick = {
                                    onInstallUpdate { permIntent ->
                                        runCatching { installPermLauncher.launch(permIntent) }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            DshSecondaryButton(
                                label = if (upd.isChecking) "Checking…" else "Check for updates",
                                icon = DshIcons.Refresh,
                                enabled = !upd.isChecking,
                                onClick = onCheckUpdates,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeModePickerRow(
    selectedMode: DshThemeMode,
    accent: AppAccent,
    onSelectMode: (DshThemeMode) -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val lightSurfaces = remember(accent) { DshSurfaces.light(accent) }
    val darkSurfaces = remember(accent) { DshSurfaces.dark(accent) }
    val lightScheme = remember(accent) { DshPalette.lightScheme(accent) }
    val darkScheme = remember(accent) { DshPalette.darkScheme(accent) }

    val modes = listOf(
        DshThemeMode.LIGHT to "Light",
        DshThemeMode.DARK to "Dark",
        DshThemeMode.SYSTEM to "System"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        modes.forEach { (mode, label) ->
            val isSelected = selectedMode == mode
            ModePreviewTile(
                label = label,
                selected = isSelected,
                lightPair = if (mode == DshThemeMode.DARK) null else (lightSurfaces to lightScheme),
                darkPair = if (mode == DshThemeMode.LIGHT) null else (darkSurfaces to darkScheme),
                onClick = {
                    if (!isSelected) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelectMode(mode)
                    }
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ModePreviewTile(
    label: String,
    selected: Boolean,
    lightPair: Pair<DshSurfaces, ColorScheme>?,
    darkPair: Pair<DshSurfaces, ColorScheme>?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val reduced = ClassRecorderTheme.reducedMotion
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduced) DshMotion.PRESS_SCALE else 1f,
        animationSpec = DshMotion.pressSpring(reduced),
        label = "modeTileScale"
    )
    val ringAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = DshMotion.quickTween(reduced),
        label = "modeRingAlpha"
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) scheme.primary else scheme.onSurfaceVariant,
        animationSpec = DshMotion.quickTween(reduced),
        label = "modeLabelColor"
    )

    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(AppShape.radiusLg)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.RadioButton,
                onClick = onClick
            )
            .semantics { this.selected = selected }
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.8f)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(5.dp)
                    .clip(AppShape.radiusMd)
                    .border(0.5.dp, scheme.outlineVariant, AppShape.radiusMd)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    if (lightPair != null && darkPair != null) {
                        drawMiniMistPhone(lightPair.first, lightPair.second, isDark = false)
                        clipRect(left = size.width * 0.5f, top = 0f, right = size.width, bottom = size.height) {
                            drawMiniMistPhone(darkPair.first, darkPair.second, isDark = true)
                        }
                    } else if (lightPair != null) {
                        drawMiniMistPhone(lightPair.first, lightPair.second, isDark = false)
                    } else if (darkPair != null) {
                        drawMiniMistPhone(darkPair.first, darkPair.second, isDark = true)
                    }
                }
            }

            if (ringAlpha > 0.01f) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokePx = 2.dp.toPx()
                    val inset = strokePx * 0.5f
                    val r = (AppShape.md + 5.dp).toPx() - inset
                    drawRoundRect(
                        color = scheme.primary.copy(alpha = ringAlpha),
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - strokePx, size.height - strokePx),
                        cornerRadius = CornerRadius(r, r),
                        style = Stroke(width = strokePx)
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            ),
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

private fun DrawScope.drawMiniMistPhone(
    s: DshSurfaces,
    scheme: ColorScheme,
    isDark: Boolean
) {
    val w = size.width
    val h = size.height
    drawRect(brush = Brush.verticalGradient(listOf(s.backdropTop, s.backdropBottom)))

    val r1 = h * 0.7f
    val c1 = Offset(w * 0.92f, 0f)
    drawCircle(
        brush = Brush.radialGradient(listOf(s.glowPrimary, s.glowPrimary.copy(alpha = 0f)), center = c1, radius = r1),
        radius = r1,
        center = c1
    )
    val r2 = h * 0.62f
    val c2 = Offset(0f, h)
    drawCircle(
        brush = Brush.radialGradient(listOf(s.glowSecondary, s.glowSecondary.copy(alpha = 0f)), center = c2, radius = r2),
        radius = r2,
        center = c2
    )

    val unit = w / 100f
    val chromeFill = s.chrome.fallbackFill
    val chromeEdge = s.chrome.hairline
    val ink = scheme.onSurface.copy(alpha = if (isDark) 0.26f else 0.20f)

    val headerW = 54f * unit
    val headerH = 9.5f * unit
    val headerTopLeft = Offset((w - headerW) * 0.5f, 7.5f * unit)
    drawRoundRect(
        color = chromeFill,
        topLeft = headerTopLeft,
        size = Size(headerW, headerH),
        cornerRadius = CornerRadius(headerH * 0.5f, headerH * 0.5f)
    )
    drawRoundRect(
        color = chromeEdge,
        topLeft = headerTopLeft,
        size = Size(headerW, headerH),
        cornerRadius = CornerRadius(headerH * 0.5f, headerH * 0.5f),
        style = Stroke(width = 0.6.dp.toPx())
    )

    val bubbleRect = Rect(w - 12f * unit - 48f * unit, 28f * unit, w - 12f * unit, 43f * unit)
    drawRoundRect(
        color = s.userBubble,
        topLeft = bubbleRect.topLeft,
        size = bubbleRect.size,
        cornerRadius = CornerRadius(6f * unit, 6f * unit)
    )
    fun drawLinePill(x: Float, y: Float, width: Float, height: Float) {
        drawRoundRect(
            color = ink,
            topLeft = Offset(x, y),
            size = Size(width, height),
            cornerRadius = CornerRadius(height * 0.5f, height * 0.5f)
        )
    }
    drawLinePill(w - 12f * unit - 40f * unit, 33.5f * unit, 32f * unit, 4f * unit)
    drawLinePill(12f * unit, 52f * unit, 66f * unit, 4.5f * unit)
    drawLinePill(12f * unit, 61f * unit, 76f * unit, 4.5f * unit)
    drawLinePill(12f * unit, 70f * unit, 48f * unit, 4.5f * unit)

    val composerH = 15f * unit
    val composerTop = h - 8f * unit - composerH
    val composerSize = Size(w - 16f * unit, composerH)
    drawRoundRect(
        color = chromeFill,
        topLeft = Offset(8f * unit, composerTop),
        size = composerSize,
        cornerRadius = CornerRadius(composerH * 0.5f, composerH * 0.5f)
    )
    drawRoundRect(
        color = chromeEdge,
        topLeft = Offset(8f * unit, composerTop),
        size = composerSize,
        cornerRadius = CornerRadius(composerH * 0.5f, composerH * 0.5f),
        style = Stroke(width = 0.6.dp.toPx())
    )
    drawCircle(
        color = scheme.primary,
        radius = composerH * 0.5f - 2.5f * unit,
        center = Offset(w - 8f * unit - composerH * 0.5f, composerTop + composerH * 0.5f)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PalettePickerRow(
    selectedAccentId: String,
    darkTheme: Boolean,
    onSelectPreset: (String) -> Unit
) {
    val haptics = LocalHapticFeedback.current
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AppAccent.presets.forEach { preset ->
            val selected = selectedAccentId == preset.id
            val swatchColor = remember(preset, darkTheme) { preset.seedFor(darkTheme) }
            AccentSwatchItem(
                label = preset.label,
                color = swatchColor,
                selected = selected,
                onClick = {
                    if (!selected) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelectPreset(preset.id)
                    }
                }
            )
        }
    }
}

@Composable
private fun AccentSwatchItem(
    label: String,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val reduced = ClassRecorderTheme.reducedMotion
    val ringAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = DshMotion.quickTween(reduced),
        label = "swatchRing_$label"
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) scheme.primary else scheme.onSurfaceVariant,
        animationSpec = DshMotion.quickTween(reduced),
        label = "swatchLabel_$label"
    )

    Column(
        modifier = Modifier
            .sizeIn(minWidth = 50.dp, minHeight = 56.dp)
            .clip(AppShape.radiusMd)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(modifier = Modifier.size(40.dp)) {
            val center = Offset(size.width * 0.5f, size.height * 0.5f)
            val discRadius = 15.dp.toPx()
            if (ringAlpha > 0.01f) {
                drawCircle(
                    color = color.copy(alpha = ringAlpha),
                    radius = discRadius + 3.5.dp.toPx(),
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )
            }
            drawCircle(
                color = color,
                radius = discRadius,
                center = center
            )
            drawCircle(
                color = scheme.onSurface.copy(alpha = 0.10f),
                radius = discRadius - 0.25.dp.toPx(),
                center = center,
                style = Stroke(width = 0.5.dp.toPx())
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            ),
            color = labelColor,
            maxLines = 1
        )
    }
}

/**
 * Background picker: choose a photo, or return to the built-in Mist gradient.
 *
 * The picked image is copied into app storage (CustomBackground.save) rather
 * than referenced by URI, so it survives the picker's permission expiring and
 * can be downscaled once instead of decoded at full size on every frame.
 */
@Composable
private fun BackgroundPickerCard(
    hasCustomBackground: Boolean,
    blurStrength: Float,
    onBlurChange: (Float) -> Unit,
    onBlurChangeFinished: () -> Unit,
    onPick: () -> Unit,
    onClear: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    ListPageCard(
        title = "Background",
        footer = if (hasCustomBackground) {
            "A dark scrim keeps text legible over your photo. The accent still tints it."
        } else {
            "Choose a photo to replace the Mist gradient behind the interface."
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SettingsIcon(icon = DshIcons.AutoAwesome, tintSlot = 2)
                Spacer(Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (hasCustomBackground) "Custom photo" else "Mist gradient",
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.onSurface
                    )
                    Text(
                        text = if (hasCustomBackground) {
                            "Tap Replace to choose another"
                        } else {
                            "The default backdrop"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DshPrimaryButton(
                    label = if (hasCustomBackground) "Replace" else "Choose photo",
                    onClick = onPick,
                    modifier = Modifier.weight(1f)
                )
                if (hasCustomBackground) {
                    DshSecondaryButton(
                        label = "Reset",
                        onClick = onClear,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (hasCustomBackground) {
                // Blur only applies to a photo; the Mist gradient has no image
                // to soften, so the control is hidden rather than disabled.
                Spacer(Modifier.size(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Blur",
                        style = MaterialTheme.typography.titleSmall,
                        color = scheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = when {
                            blurStrength <= 0.01f -> "Off"
                            blurStrength < 0.35f -> "Light"
                            blurStrength < 0.7f -> "Medium"
                            else -> "Heavy"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.primary
                    )
                }
                Slider(
                    value = blurStrength,
                    onValueChange = onBlurChange,
                    // Re-deriving the bitmap is a decode plus a box blur, far too
                    // heavy for every pixel of travel - commit on release.
                    onValueChangeFinished = onBlurChangeFinished,
                    valueRange = 0f..1f,
                    steps = 0,
                    colors = SliderDefaults.colors(
                        thumbColor = scheme.primary,
                        activeTrackColor = scheme.primary,
                        inactiveTrackColor = scheme.surfaceContainerHighest
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

