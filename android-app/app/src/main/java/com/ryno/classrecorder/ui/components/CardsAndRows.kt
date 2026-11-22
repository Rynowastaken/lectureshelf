package com.ryno.classrecorder.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ryno.classrecorder.ui.theme.AppShape
import com.ryno.classrecorder.ui.theme.ClassRecorderTheme
import com.ryno.classrecorder.ui.theme.DshMotion
import com.ryno.classrecorder.ui.theme.GlassTier
import com.ryno.classrecorder.ui.theme.StatusTone
import com.ryno.classrecorder.ui.theme.tabular

/**
 * Shared DSH Go switch palette used across settings tiles and overview cards.
 */
@Composable
fun dshSwitchColors(): SwitchColors {
    val scheme = MaterialTheme.colorScheme
    return SwitchDefaults.colors(
        checkedThumbColor = scheme.onPrimary,
        checkedTrackColor = scheme.primary,
        uncheckedThumbColor = scheme.onSurfaceVariant,
        uncheckedTrackColor = scheme.surfaceContainerHighest,
        uncheckedBorderColor = scheme.outlineVariant
    )
}

/**
 * Translucent stadium mini-pill used for `#tags`, slot codes, and metadata badges.
 */
@Composable
fun QuietTagPill(
    text: String,
    modifier: Modifier = Modifier,
    tabularFigures: Boolean = false
) {
    val surfaces = ClassRecorderTheme.surfaces
    val scheme = MaterialTheme.colorScheme
    val baseStyle = MaterialTheme.typography.labelMedium
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(
                scheme.onSurface.copy(alpha = if (surfaces.darkTheme) 0.085f else 0.06f)
            )
            .padding(horizontal = 9.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            style = if (tabularFigures) baseStyle.tabular() else baseStyle,
            color = scheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/**
 * Hub overview card ported from DSH Go's overview_card.dart.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OverviewCard(
    leading: @Composable () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleAtoms: List<String> = emptyList(),
    titleBadge: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    footer: (@Composable RowScope.() -> Unit)? = null,
    onFooterClick: (() -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    val surfaces = ClassRecorderTheme.surfaces
    val dividerColor = if (surfaces.darkTheme) {
        Color.White.copy(alpha = 0.08f)
    } else {
        scheme.onSurface.copy(alpha = 0.08f)
    }

    GlassSurface(
        modifier = modifier.fillMaxWidth(),
        tier = GlassTier.Card,
        shape = AppShape.radiusLg
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (onClick != null) {
                            Modifier.clickable(role = Role.Button, onClick = onClick)
                        } else Modifier
                    )
                    .padding(
                        start = 16.dp,
                        top = 16.dp,
                        end = if (trailing != null || (onClick != null && titleBadge == null)) 12.dp else 16.dp,
                        bottom = 16.dp
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(52.dp),
                    contentAlignment = Alignment.Center
                ) {
                    leading()
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            color = scheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (titleBadge != null) {
                            titleBadge()
                        }
                    }
                    if (subtitleAtoms.isNotEmpty()) {
                        Spacer(Modifier.height(3.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(0.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            subtitleAtoms.forEachIndexed { idx, atom ->
                                val trailingSep = idx < subtitleAtoms.lastIndex
                                Text(
                                    text = if (trailingSep) "$atom · " else atom,
                                    style = MaterialTheme.typography.bodyMedium.tabular(),
                                    color = scheme.onSurfaceVariant,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    } else if (!subtitle.isNullOrBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium.tabular(),
                            color = scheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (trailing != null) {
                    Spacer(Modifier.width(8.dp))
                    trailing()
                } else if (onClick != null && titleBadge == null) {
                    Spacer(Modifier.width(4.dp))
                    OverviewChevron()
                }
            }

            if (footer != null) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    thickness = 0.5.dp,
                    color = dividerColor
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .then(
                            if (onFooterClick != null) {
                                Modifier.clickable(role = Role.Button, onClick = onFooterClick)
                            } else Modifier
                        )
                        .padding(start = 0.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        content = footer
                    )
                    if (onFooterClick != null) {
                        Spacer(Modifier.width(4.dp))
                        OverviewChevron()
                    }
                }
            }
        }
    }
}

/**
 * Standard [OverviewCard] footer alignment: centers a [StatusDot] under the 52dp
 * avatar column and aligns the status text directly beneath the card title.
 */
@Composable
fun RowScope.OverviewFooterRow(
    tone: StatusTone,
    text: String
) {
    val reduced = ClassRecorderTheme.reducedMotion
    Box(
        modifier = Modifier
            .width(16.dp + 52.dp)
            .padding(start = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        StatusDot(tone = tone, size = 8.dp)
    }
    Spacer(Modifier.width(14.dp))
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            fadeIn(DshMotion.quickTween(reduced)) togetherWith fadeOut(DshMotion.quickTween(reduced))
        },
        modifier = Modifier.weight(1f),
        label = "overviewFooterText"
    ) { current ->
        Text(
            text = current,
            style = MaterialTheme.typography.bodySmall.tabular(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 52dp circular identity-tinted badge for an [OverviewCard] or [PageHero].
 */
@Composable
fun OverviewGlyph(
    icon: ImageVector,
    identityKey: String = "",
    modifier: Modifier = Modifier,
    tintSlot: Int? = null
) {
    val dsh = ClassRecorderTheme.dshColors
    val (bg, fg) = if (tintSlot != null) {
        dsh.identityAt(tintSlot)
    } else {
        dsh.identityPair(identityKey)
    }
    Box(
        modifier = modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = fg
        )
    }
}

/**
 * 32dp circular identity-tinted disc for settings and list rows.
 */
@Composable
fun SettingsIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tintSlot: Int = 0,
    identityKey: String? = null,
    danger: Boolean = false
) {
    val dsh = ClassRecorderTheme.dshColors
    val (bg, fg) = when {
        danger -> dsh.dangerContainer to dsh.onDangerContainer
        identityKey != null -> dsh.identityPair(identityKey)
        else -> dsh.identityAt(tintSlot)
    }
    Box(
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = fg
        )
    }
}

@Composable
private fun OverviewChevron(modifier: Modifier = Modifier) {
    Icon(
        imageVector = DshIcons.ChevronRight,
        contentDescription = null,
        modifier = modifier
            .size(20.dp)
            .offset(x = 3.dp),
        tint = ClassRecorderTheme.dshColors.textTertiary
    )
}

/**
 * Card-tier glass group for settings sections and day-grouped rows.
 */
@Composable
fun ListPageCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    trailingTitle: String? = null,
    footer: String? = null,
    contentPadding: PaddingValues = PaddingValues(vertical = 4.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val dsh = ClassRecorderTheme.dshColors
    Column(modifier = modifier.fillMaxWidth()) {
        if (!title.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = dsh.textTertiary,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() }
                )
                if (!trailingTitle.isNullOrBlank()) {
                    Text(
                        text = trailingTitle,
                        style = MaterialTheme.typography.bodySmall.tabular(),
                        color = dsh.textTertiary
                    )
                }
            }
        }
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            tier = GlassTier.Card,
            shape = AppShape.radiusLg,
            contentPadding = contentPadding
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                content = content
            )
        }
        if (!footer.isNullOrBlank()) {
            Text(
                text = footer,
                style = MaterialTheme.typography.bodySmall,
                color = dsh.textTertiary,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp)
            )
        }
    }
}

/**
 * Subtle 0.5dp inset divider for separating multi-line rows inside a [ListPageCard].
 */
@Composable
fun ListCardDivider(startIndent: Dp = 16.dp) {
    HorizontalDivider(
        modifier = Modifier.padding(start = startIndent, end = 16.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    )
}

/**
 * Shared row anatomy ported from DSH Go's ListPageRow / SettingsTile.
 */
@Composable
fun ListPageRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
    meta: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = onClick != null && trailing == null
) {
    val scheme = MaterialTheme.colorScheme
    val dsh = ClassRecorderTheme.dshColors
    val minHeight = if (!subtitle.isNullOrBlank()) 64.dp else 52.dp
    val interactionSource = remember { MutableInteractionSource() }

    val clickMod = if (onClick != null) {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = ripple(),
            onClick = onClick
        )
    } else Modifier

    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = minHeight)
            .then(clickMod)
            .padding(
                start = 16.dp,
                top = 10.dp,
                end = if (showChevron || trailing != null) 12.dp else 16.dp,
                bottom = 10.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null || icon != null) {
            Box(
                modifier = Modifier.width(32.dp),
                contentAlignment = Alignment.Center
            ) {
                if (leading != null) {
                    leading()
                } else if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = scheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Normal
                ),
                color = scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.tabular(),
                    color = dsh.textTertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (!meta.isNullOrBlank()) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall.tabular(),
                color = dsh.textTertiary,
                maxLines = 1
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
        if (showChevron) {
            Spacer(Modifier.width(4.dp))
            OverviewChevron()
        }
    }
}

/**
 * Full-row toggle tile ported from DSH Go's SettingsSwitchTile.
 */
@Composable
fun SettingsSwitchTile(
    title: String,
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null
) {
    val haptics = LocalHapticFeedback.current
    val toggleModifier = if (onCheckedChange != null) {
        modifier.toggleable(
            value = checked,
            role = Role.Switch,
            onValueChange = { next ->
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onCheckedChange(next)
            }
        )
    } else modifier

    ListPageRow(
        title = title,
        subtitle = subtitle,
        modifier = toggleModifier,
        leading = leading,
        onClick = null,
        showChevron = false,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                colors = dshSwitchColors()
            )
        }
    )
}

/**
 * Recessed monospace well for MCP URLs, ADB commands, and generated filenames.
 */
@Composable
fun CodeWell(
    text: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    onCopy: (() -> Unit)? = null
) {
    val dsh = ClassRecorderTheme.dshColors
    val mono = ClassRecorderTheme.mono

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(AppShape.radiusSm)
            .background(dsh.codeWellBg)
            .border(0.75.dp, dsh.codeWellBorder, AppShape.radiusSm)
    ) {
        if (!label.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(dsh.codeWellHeader)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = dsh.textTertiary,
                    modifier = Modifier.weight(1f)
                )
            }
            HorizontalDivider(thickness = 0.5.dp, color = dsh.codeWellBorder)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .then(
                    if (onCopy != null) {
                        Modifier.clickable(
                            role = Role.Button,
                            onClickLabel = "Copy",
                            onClick = onCopy
                        )
                    } else Modifier
                )
                .padding(start = 12.dp, top = 8.dp, end = if (onCopy != null) 4.dp else 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                style = mono.monoSmall,
                color = dsh.codeWellFg,
                modifier = Modifier.weight(1f)
            )
            if (onCopy != null) {
                Box(
                    modifier = Modifier.size(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = DshIcons.ContentCopy,
                        contentDescription = "Copy",
                        modifier = Modifier.size(16.dp),
                        tint = dsh.textTertiary
                    )
                }
            }
        }
    }
}

/**
 * Recessed borderless text input field matching DSH Go's quiet capsule/well inputs.
 */
@Composable
fun DshTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    label: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 6,
    mono: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    imeAction: ImeAction = if (singleLine) ImeAction.Done else ImeAction.Default,
    onImeAction: (() -> Unit)? = null
) {
    val surfaces = ClassRecorderTheme.surfaces
    val dsh = ClassRecorderTheme.dshColors
    val scheme = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current

    val wellFill = if (surfaces.darkTheme) {
        Color.White.copy(alpha = 0.065f)
    } else {
        scheme.onSurface.copy(alpha = 0.05f)
    }
    val textStyle = if (mono) {
        ClassRecorderTheme.mono.mono.copy(color = scheme.onSurface)
    } else {
        MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (!label.isNullOrBlank()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = dsh.textTertiary,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .clip(AppShape.radiusMd)
                .background(wellFill)
                .border(
                    width = 0.5.dp,
                    color = scheme.outlineVariant.copy(alpha = 0.6f),
                    shape = AppShape.radiusMd
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top
        ) {
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        text = placeholder,
                        style = textStyle.copy(color = dsh.textTertiary),
                        maxLines = if (singleLine) 1 else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = singleLine,
                    minLines = minLines,
                    maxLines = maxLines,
                    textStyle = textStyle,
                    cursorBrush = SolidColor(scheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = imeAction),
                    keyboardActions = KeyboardActions(
                        onAny = {
                            onImeAction?.invoke() ?: focusManager.clearFocus()
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
    }
}

/**
 * Centered empty / not-yet state ported from DSH Go's empty_view.dart.
 */
@Composable
fun EmptyView(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    primaryAction: Boolean = false,
    compact: Boolean = false
) {
    val dsh = ClassRecorderTheme.dshColors
    val scheme = MaterialTheme.colorScheme
    val badgeSize = if (compact) 48.dp else 64.dp
    val iconSize = if (compact) 22.dp else 28.dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = if (compact) 24.dp else 32.dp, vertical = if (compact) 20.dp else 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        GlassSurface(
            modifier = Modifier.size(badgeSize),
            tier = GlassTier.Card,
            circle = true
        ) {
            Box(
                modifier = Modifier.size(badgeSize),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(iconSize),
                    tint = scheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(if (compact) 14.dp else 18.dp))
        Text(
            text = title,
            style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
            color = scheme.onSurface,
            textAlign = TextAlign.Center
        )
        if (!message.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = message,
                style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                color = dsh.textTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.sizeIn(maxWidth = 300.dp)
            )
        }
        if (!actionLabel.isNullOrBlank() && onAction != null) {
            Spacer(Modifier.height(if (compact) 14.dp else 20.dp))
            if (primaryAction) {
                DshPrimaryButton(
                    label = actionLabel,
                    onClick = onAction
                )
            } else {
                DshSecondaryButton(
                    label = actionLabel,
                    onClick = onAction
                )
            }
        }
    }
}

/**
 * Primary stadium action button with DSH Go press-scale spring and >= 48dp hit height.
 */
@Composable
fun DshPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
    minHeight: Dp = 48.dp
) {
    val scheme = MaterialTheme.colorScheme
    val dsh = ClassRecorderTheme.dshColors
    val reduced = ClassRecorderTheme.reducedMotion
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (enabled && pressed && !reduced) DshMotion.PRESS_SCALE else 1f,
        animationSpec = DshMotion.pressSpring(reduced),
        label = "primaryBtnScale"
    )
    val bg = when {
        !enabled -> scheme.surfaceContainerHighest
        danger -> dsh.danger
        else -> scheme.primary
    }
    val fg = when {
        !enabled -> dsh.textDisabled
        danger -> dsh.onDanger
        else -> scheme.onPrimary
    }

    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .defaultMinSize(minHeight = minHeight)
            .clip(CircleShape)
            .background(bg)
            .then(
                if (enabled) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = ripple(),
                        role = Role.Button,
                        onClick = onClick
                    )
                } else Modifier
            )
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = fg
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Quiet Pill-tier stadium secondary button with >= 48dp touch height.
 */
@Composable
fun DshSecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
    minHeight: Dp = 48.dp
) {
    val scheme = MaterialTheme.colorScheme
    val dsh = ClassRecorderTheme.dshColors
    val fg = when {
        !enabled -> dsh.textDisabled
        danger -> dsh.danger
        else -> scheme.onSurface
    }
    GlassSurface(
        modifier = modifier.defaultMinSize(minHeight = minHeight),
        tier = GlassTier.Pill,
        stadium = true,
        enabled = enabled,
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = fg
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
