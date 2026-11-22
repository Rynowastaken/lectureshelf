package com.ryno.classrecorder.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ryno.classrecorder.core.CourseSlot
import com.ryno.classrecorder.ui.MainUiState
import com.ryno.classrecorder.ui.components.AppSheet
import com.ryno.classrecorder.ui.components.BackdropVeil
import com.ryno.classrecorder.ui.components.BindScrollUnderVeil
import com.ryno.classrecorder.ui.components.DshIcons
import com.ryno.classrecorder.ui.components.DshPrimaryButton
import com.ryno.classrecorder.ui.components.DshTextField
import com.ryno.classrecorder.ui.components.EmptyView
import com.ryno.classrecorder.ui.components.GlassIconButton
import com.ryno.classrecorder.ui.components.GlassSurface
import com.ryno.classrecorder.ui.components.QuietTagPill
import com.ryno.classrecorder.ui.components.StatusChip
import com.ryno.classrecorder.ui.components.rememberSafeSideInsets
import com.ryno.classrecorder.ui.theme.AppShape
import com.ryno.classrecorder.ui.theme.AppSpace
import com.ryno.classrecorder.ui.theme.ClassRecorderTheme
import com.ryno.classrecorder.ui.theme.DshMotion
import com.ryno.classrecorder.ui.theme.GlassTier
import com.ryno.classrecorder.ui.theme.StatusTone
import com.ryno.classrecorder.ui.theme.tabular
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

private val SHORT_DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val FULL_DAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

private data class ScheduledPeriod(
    val slot: String,
    val timeRange: String,
    val course: CourseSlot,
    val isNow: Boolean,
    val isNext: Boolean
)

/**
 * Weekly timetable screen rebuilt in DSH Go's Mist Glass language.
 */
@Composable
fun ScheduleScreen(
    ui: MainUiState,
    onSyncSchedule: (String) -> Unit,
    onRecordCourse: (CourseSlot, String) -> Unit,
    topContentInset: Dp = 0.dp,
    onScrolledUnderChange: (Float) -> Unit = {}
) {
    val haptics = LocalHapticFeedback.current
    val reduced = ClassRecorderTheme.reducedMotion
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val listState = rememberLazyListState()

    BindScrollUnderVeil(listState = listState, onScrolledUnderChange = onScrolledUnderChange)

    val canScrollDown by remember { derivedStateOf { listState.canScrollForward } }
    val bottomVeilAlpha by animateFloatAsState(
        targetValue = if (canScrollDown) 0.94f else 0f,
        animationSpec = DshMotion.quickTween(reduced),
        label = "scheduleBottomVeil"
    )

    val today = remember { LocalDate.now() }
    val todayIndex = remember(today) { (today.dayOfWeek.value - 1).coerceIn(0, 6) }
    val monday = remember(today) { today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
    var selectedDay by remember { mutableIntStateOf(todayIndex) }
    var showSourceSheet by remember { mutableStateOf(false) }

    val schedule = ui.schedule
    val dayCoursesByIndex = remember(schedule, ui.currentClass, todayIndex) {
        (0..6).map { dayIdx ->
            schedule?.slots.orEmpty().mapNotNull { row ->
                val c = row.days.getOrNull(dayIdx)
                if (c == null || c.name.isBlank()) null
                else {
                    val matchesCurrent = dayIdx == todayIndex &&
                        ui.currentClass?.weekdayIndex == dayIdx &&
                        ui.currentClass.slot == row.slot &&
                        ui.currentClass.course.name == c.name
                    ScheduledPeriod(
                        slot = row.slot,
                        timeRange = UiFormatters.formatTimeRange(row.time),
                        course = c,
                        isNow = matchesCurrent && ui.currentClass?.kind != "upcoming",
                        isNext = matchesCurrent && ui.currentClass?.kind == "upcoming"
                    )
                }
            }
        }
    }

    val selectedPeriods = dayCoursesByIndex.getOrElse(selectedDay) { emptyList() }
    val todayCount = dayCoursesByIndex.getOrElse(todayIndex) { emptyList() }.size

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val (startInset, endInset) = rememberSafeSideInsets(maxWidth)

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = AppSpace.gutter + startInset,
                end = AppSpace.gutter + endInset,
                top = topContentInset + 8.dp,
                bottom = navBottom + 44.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "schedule_header") {
                ScheduleHeaderBar(
                    schoolName = schedule?.schoolName?.ifBlank { "Class schedule" } ?: "Class schedule",
                    subtitle = when {
                        ui.isSyncing -> "Syncing timetable…"
                        schedule == null -> "Import your timetable share link"
                        selectedDay == todayIndex -> if (todayCount == 1) "Today · 1 class" else "Today · $todayCount classes"
                        else -> "${FULL_DAYS[selectedDay]} · ${selectedPeriods.size} ${if (selectedPeriods.size == 1) "class" else "classes"}"
                    },
                    isSyncing = ui.isSyncing,
                    showRefresh = schedule != null,
                    onRefresh = {
                        val url = schedule?.sourceUrl?.takeIf { it.isNotBlank() } ?: ui.scheduleSourceInput
                        if (url.isNotBlank()) onSyncSchedule(url) else showSourceSheet = true
                    },
                    onOpenSource = { showSourceSheet = true }
                )
            }

            item(key = "week_strip") {
                WeekStripCard(
                    monday = monday,
                    todayIndex = todayIndex,
                    selectedDay = selectedDay,
                    dayCoursesByIndex = dayCoursesByIndex,
                    onSelectDay = { idx ->
                        if (idx != selectedDay) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            selectedDay = idx
                        }
                    }
                )
            }

            when {
                schedule == null -> {
                    item(key = "empty_no_schedule") {
                        Spacer(Modifier.height(12.dp))
                        EmptyView(
                            icon = DshIcons.CalendarMonth,
                            title = "No schedule synced",
                            message = "Import your uni-campus share link or NUTC timetable so recordings are tagged automatically.",
                            actionLabel = "Sync schedule",
                            onAction = { showSourceSheet = true },
                            primaryAction = true
                        )
                    }
                }
                selectedPeriods.isEmpty() -> {
                    item(key = "empty_day_$selectedDay") {
                        Spacer(Modifier.height(12.dp))
                        EmptyView(
                            icon = DshIcons.EventAvailable,
                            title = "No classes on ${FULL_DAYS[selectedDay]}",
                            message = "Select another weekday above to browse upcoming lectures.",
                            actionLabel = if (selectedDay != todayIndex) "Jump to today" else null,
                            onAction = if (selectedDay != todayIndex) {
                                { selectedDay = todayIndex }
                            } else null
                        )
                    }
                }
                else -> {
                    items(
                        items = selectedPeriods,
                        key = { "${selectedDay}_${it.slot}_${it.course.name}" }
                    ) { period ->
                        PeriodClassCard(
                            period = period,
                            onRecord = { onRecordCourse(period.course, period.slot) }
                        )
                    }
                }
            }
        }

        BackdropVeil(
            fromTop = false,
            solidHeight = 0.dp,
            feather = navBottom + 4.dp,
            alpha = bottomVeilAlpha * 0.65f,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    if (showSourceSheet) {
        ScheduleSourceSheet(
            initialUrl = ui.scheduleSourceInput,
            isSyncing = ui.isSyncing,
            onSync = { url ->
                onSyncSchedule(url)
                showSourceSheet = false
            },
            onDismiss = { showSourceSheet = false }
        )
    }
}

@Composable
private fun ScheduleHeaderBar(
    schoolName: String,
    subtitle: String,
    isSyncing: Boolean,
    showRefresh: Boolean,
    onRefresh: () -> Unit,
    onOpenSource: () -> Unit
) {
    val dsh = ClassRecorderTheme.dshColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = schoolName,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.tabular(),
                color = dsh.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        if (showRefresh) {
            GlassIconButton(
                icon = DshIcons.Refresh,
                contentDescription = "Refresh schedule",
                onClick = onRefresh,
                tier = GlassTier.Pill,
                visualSize = 40.dp,
                badgeTone = if (isSyncing) StatusTone.Busy else null
            )
        }
        GlassIconButton(
            icon = DshIcons.Link,
            contentDescription = "Schedule source link",
            onClick = onOpenSource,
            tier = GlassTier.Pill,
            visualSize = 40.dp
        )
    }
}

@Composable
private fun WeekStripCard(
    monday: LocalDate,
    todayIndex: Int,
    selectedDay: Int,
    dayCoursesByIndex: List<List<ScheduledPeriod>>,
    onSelectDay: (Int) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val dsh = ClassRecorderTheme.dshColors
    val reduced = ClassRecorderTheme.reducedMotion
    val density = LocalDensity.current

    val animatedSlot by animateFloatAsState(
        targetValue = selectedDay.toFloat(),
        animationSpec = DshMotion.thumbSpring(reduced),
        label = "weekStripThumb"
    )

    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp),
        tier = GlassTier.Card,
        shape = AppShape.radiusLg,
        contentPadding = PaddingValues(4.dp)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val cellWidth = maxWidth / 7f
            val cellWidthPx = with(density) { cellWidth.toPx() }

            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = (animatedSlot * cellWidthPx).roundToInt(),
                            y = 0
                        )
                    }
                    .width(cellWidth)
                    .fillMaxHeight()
                    .clip(AppShape.radiusMd)
                    .background(scheme.primary)
            )

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .selectableGroup()
            ) {
                for (dayIdx in 0..6) {
                    val isSelected = dayIdx == selectedDay
                    val isToday = dayIdx == todayIndex
                    val dateNum = monday.plusDays(dayIdx.toLong()).dayOfMonth.toString()
                    val dayClasses = dayCoursesByIndex.getOrElse(dayIdx) { emptyList() }

                    val dayFg by animateColorAsState(
                        targetValue = if (isSelected) scheme.onPrimary else scheme.onSurface,
                        animationSpec = DshMotion.quickTween(reduced),
                        label = "weekDayFg$dayIdx"
                    )
                    val subFg by animateColorAsState(
                        targetValue = if (isSelected) {
                            scheme.onPrimary.copy(alpha = 0.82f)
                        } else {
                            dsh.textTertiary
                        },
                        animationSpec = DshMotion.quickTween(reduced),
                        label = "weekSubFg$dayIdx"
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(AppShape.radiusMd)
                            .selectable(
                                selected = isSelected,
                                role = Role.Tab,
                                onClick = { onSelectDay(dayIdx) }
                            )
                            .padding(vertical = 8.dp, horizontal = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = SHORT_DAYS[dayIdx],
                                style = MaterialTheme.typography.labelSmall,
                                color = subFg,
                                maxLines = 1,
                                softWrap = false
                            )
                            if (isToday) {
                                Box(
                                    modifier = Modifier
                                        .size(4.dp)
                                        .clip(CircleShape)
                                        .background(if (isSelected) scheme.onPrimary else scheme.primary)
                                )
                            }
                        }

                        Text(
                            text = dateNum,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontSize = 19.sp,
                                fontWeight = FontWeight.SemiBold
                            ).tabular(),
                            color = dayFg,
                            maxLines = 1,
                            softWrap = false
                        )

                        Row(
                            modifier = Modifier.height(6.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            dayClasses.take(5).forEach { p ->
                                val pipColor = dsh.courseDot(
                                    key = p.course.name,
                                    onDarkSurface = ClassRecorderTheme.surfaces.darkTheme
                                )
                                Box(
                                    modifier = Modifier
                                        .size(width = 5.5.dp, height = 4.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isSelected) scheme.onPrimary.copy(alpha = 0.90f)
                                            else pipColor
                                        )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PeriodClassCard(
    period: ScheduledPeriod,
    onRecord: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val dsh = ClassRecorderTheme.dshColors
    val surfaces = ClassRecorderTheme.surfaces
    val dotColor = dsh.courseDot(period.course.name, surfaces.darkTheme)
    val detailLine = listOfNotNull(
        period.course.room.takeIf { it.isNotBlank() },
        period.course.teacher.takeIf { it.isNotBlank() }
    ).joinToString(" · ")

    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        tier = GlassTier.Card,
        shape = AppShape.radiusLg,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.width(92.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = period.timeRange.ifBlank { "--:--" },
                    style = MaterialTheme.typography.labelSmall.tabular(),
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false
                )
                QuietTagPill(
                    text = "P${period.slot}",
                    tabularFigures = true
                )
            }

            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                    Text(
                        text = period.course.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    when {
                        period.isNow -> StatusChip(tone = StatusTone.Ok, label = "Now")
                        period.isNext -> StatusChip(tone = StatusTone.Busy, label = "Next")
                    }
                }
                if (detailLine.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = detailLine,
                        style = MaterialTheme.typography.bodySmall.tabular(),
                        color = dsh.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 17.dp)
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            if (period.isNow) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(scheme.primary)
                        .clickable(
                            role = Role.Button,
                            onClickLabel = "Record ${period.course.name}",
                            onClick = onRecord
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = DshIcons.Mic,
                        contentDescription = "Record ${period.course.name}",
                        tint = scheme.onPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else {
                GlassIconButton(
                    icon = DshIcons.Mic,
                    contentDescription = "Record ${period.course.name}",
                    onClick = onRecord,
                    tier = GlassTier.Pill,
                    visualSize = 40.dp,
                    iconSize = 18.dp
                )
            }
        }
    }
}

@Composable
private fun ScheduleSourceSheet(
    initialUrl: String,
    isSyncing: Boolean,
    onSync: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var input by remember(initialUrl) { mutableStateOf(initialUrl) }
    val dsh = ClassRecorderTheme.dshColors

    AppSheet(
        onDismiss = onDismiss,
        title = "Schedule source"
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpace.sheetGutter),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Paste your uni-campus share link or NUTC timetable URL to keep course names, rooms, and period times synced.",
                style = MaterialTheme.typography.bodySmall,
                color = dsh.textTertiary
            )

            DshTextField(
                value = input,
                onValueChange = { input = it },
                label = "Share URL or code",
                placeholder = "https://uni-campus-app.pages.dev/s/...",
                mono = true,
                imeAction = ImeAction.Go,
                onImeAction = {
                    if (input.isNotBlank()) onSync(input.trim())
                }
            )

            DshPrimaryButton(
                label = if (isSyncing) "Syncing…" else "Sync schedule",
                enabled = input.isNotBlank() && !isSyncing,
                onClick = { onSync(input.trim()) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
