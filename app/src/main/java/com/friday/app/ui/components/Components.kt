package com.friday.app.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.friday.app.R
import com.friday.app.ui.theme.Friday
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Extra bottom space the current screen must leave for the tab bar (zero on pushed screens). */
val LocalTabBarHeight = staticCompositionLocalOf { 0.dp }

class Haptics(private val view: View) {
    fun tick() = view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    fun confirm() = view.performHapticFeedback(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY,
    )
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

@Composable
fun Ic(@DrawableRes id: Int, modifier: Modifier = Modifier, tint: Color = Friday.colors.text, size: Dp = 22.dp, description: String? = null) =
    Icon(painterResource(id), description, modifier.size(size), tint = tint)

/** Scale-down press feedback instead of a ripple, like iOS buttons. */
@Composable
fun Modifier.pressable(onClick: () -> Unit, enabled: Boolean = true, label: String? = null): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed && !Friday.reduceMotion) 0.96f else 1f,
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interaction, indication = null, enabled = enabled, onClickLabel = label, role = Role.Button, onClick = onClick)
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    color: Color = Friday.colors.accent,
) {
    val c = Friday.colors
    Row(
        modifier
            .pressable(onClick, enabled)
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) color else c.fill)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Ic(icon, tint = if (enabled) Color.White else c.textTertiary, size = 20.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) Color.White else c.textTertiary)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    color: Color = Friday.colors.accent,
    height: Dp = 48.dp,
) {
    Row(
        modifier
            .pressable(onClick)
            .heightIn(min = height)
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = if (Friday.colors.isDark) 0.2f else 0.1f))
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Ic(icon, tint = color, size = 20.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Friday.colors.accent, bold: Boolean = false) {
    Text(
        text,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick, role = Role.Button)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal),
        color = color,
    )
}

@Composable
fun BarIcon(@DrawableRes icon: Int, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Friday.colors.accent) {
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick, onClickLabel = description, role = Role.Button),
        contentAlignment = Alignment.Center,
    ) { Ic(icon, tint = tint, size = 24.dp, description = description) }
}

@Composable
fun RoundIconButton(
    @DrawableRes icon: Int,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 38.dp,
) {
    val c = Friday.colors
    Box(
        modifier
            .size(44.dp)
            .pressable(onClick, enabled, description),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(if (filled) (if (enabled) c.accent else c.fill) else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Ic(icon, tint = if (filled) (if (enabled) Color.White else c.textTertiary) else c.accent, size = 22.dp, description = description)
        }
    }
}

@Composable
fun Section(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    inset: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Friday.colors
    Column(modifier.fillMaxWidth().padding(horizontal = inset)) {
        if (header != null) {
            Text(
                header,
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp).semantics { heading() },
            )
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface), content = content)
        if (footer != null) {
            Text(footer, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))
        }
    }
}

@Composable
fun RowDivider(start: Dp = 16.dp) {
    HorizontalDivider(Modifier.padding(start = start), thickness = 0.5.dp, color = Friday.colors.separator)
}

@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    @DrawableRes icon: Int? = null,
    iconBackground: Color? = null,
    titleColor: Color = Friday.colors.text,
    onClick: (() -> Unit)? = null,
    chevron: Boolean = onClick != null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Friday.colors
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            if (iconBackground != null) {
                Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(iconBackground), contentAlignment = Alignment.Center) {
                    Ic(icon, tint = Color.White, size = 19.dp)
                }
            } else {
                Ic(icon, tint = c.accent, size = 22.dp)
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        }
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                color = c.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        } else if (chevron) {
            Spacer(Modifier.width(4.dp))
            Ic(R.drawable.ic_chevron_right, tint = c.textTertiary, size = 20.dp)
        }
    }
}

@Composable
fun FridaySwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val c = Friday.colors
    val haptics = rememberHaptics()
    Switch(
        checked = checked,
        onCheckedChange = { haptics.tick(); onCheckedChange(it) },
        enabled = enabled,
        thumbContent = { Box(Modifier.size(SwitchDefaults.IconSize)) },
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = c.green,
            checkedBorderColor = Color.Transparent,
            checkedIconColor = Color.Transparent,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = c.fill,
            uncheckedBorderColor = Color.Transparent,
            uncheckedIconColor = Color.Transparent,
        ),
    )
}

@Composable
fun SwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, subtitle: String? = null, @DrawableRes icon: Int? = null, iconBackground: Color? = null) {
    ListRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        iconBackground = iconBackground,
        onClick = { onCheckedChange(!checked) },
        chevron = false,
        trailing = { FridaySwitch(checked, onCheckedChange) },
    )
}

@Composable
fun Pill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
) {
    val c = Friday.colors
    val bg = if (selected) c.accent else c.surface
    val fg = if (selected) Color.White else c.text
    Row(
        modifier
            .heightIn(min = 36.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick, role = Role.Tab)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Ic(icon, tint = fg, size = 16.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = fg)
    }
}

@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Friday.colors
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(c.fill)
            .padding(2.dp),
    ) {
        val w = maxWidth / options.size
        val x by animateDpAsState(w * selected, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow), label = "segment")
        Box(
            Modifier
                .offset(x = x)
                .width(w)
                .fillMaxHeight()
                .shadow(if (c.isDark) 0.dp else 1.dp, RoundedCornerShape(8.dp))
                .background(if (c.isDark) Color(0xFF636366) else Color.White, RoundedCornerShape(8.dp)),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, label ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(interactionSource = null, indication = null, role = Role.Tab) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Medium),
                        color = c.text,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
fun BackButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Friday.colors
    Row(
        modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick, role = Role.Button)
            .padding(start = 6.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ic(R.drawable.ic_arrow_back_ios_new, tint = c.accent, size = 20.dp)
        Spacer(Modifier.width(2.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = c.accent)
    }
}

/**
 * iOS-style screen: a large title that collapses into a small centered title with a hairline
 * once content scrolls under it.
 */
@Composable
fun LargeTitleScreen(
    title: String,
    modifier: Modifier = Modifier,
    backLabel: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    state: LazyListState = rememberLazyListState(),
    bottomExtra: Dp = 24.dp,
    overlay: (@Composable BoxScope.() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val c = Friday.colors
    val collapsed by remember { derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 50 } }
    val barAlpha by animateFloatAsState(if (collapsed) 1f else 0f, tween(160), label = "bar")
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(modifier.fillMaxSize().background(c.background)) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = top + 44.dp, bottom = maxOf(LocalTabBarHeight.current, bottom) + bottomExtra),
        ) {
            item(key = "__title") {
                Text(
                    title,
                    style = MaterialTheme.typography.displaySmall,
                    color = c.text,
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .padding(top = 2.dp, bottom = 14.dp)
                        .semantics { heading() },
                )
            }
            content()
        }
        Box(
            Modifier
                .fillMaxWidth()
                .background(c.background.copy(alpha = 0.97f * barAlpha))
                .statusBarsPadding()
                .height(44.dp),
        ) {
            if (onBack != null) BackButton(backLabel ?: stringResource(R.string.back), onBack, Modifier.align(Alignment.CenterStart).padding(start = 4.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 96.dp).alpha(barAlpha),
            )
            Row(Modifier.align(Alignment.CenterEnd).padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
            HorizontalDivider(Modifier.align(Alignment.BottomCenter).alpha(barAlpha), thickness = 0.5.dp, color = c.separator)
        }
        overlay?.invoke(this)
    }
}

@Composable
fun EmptyState(
    @DrawableRes icon: Int,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val c = Friday.colors
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(60.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
            Ic(icon, tint = c.accent, size = 28.dp)
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = c.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, textAlign = TextAlign.Center)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(18.dp))
            SecondaryButton(action, onAction)
        }
    }
}

@Composable
fun CheckCircle(checked: Boolean, onToggle: () -> Unit, description: String, modifier: Modifier = Modifier) {
    val c = Friday.colors
    val scale by animateFloatAsState(
        if (checked) 1f else 0.92f,
        spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium),
        label = "check",
    )
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onToggle, onClickLabel = description, role = Role.Checkbox),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(24.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(CircleShape)
                .then(if (checked) Modifier.background(c.accent) else Modifier.border(1.5.dp, c.textTertiary, CircleShape)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Ic(R.drawable.ic_check, tint = Color.White, size = 16.dp)
        }
    }
}

/** A task line with an iOS-Reminders style completion circle. Completion animates before [onToggle] runs. */
@Composable
fun TaskRow(
    title: String,
    done: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    meta: String? = null,
    metaColor: Color = Friday.colors.textSecondary,
    important: Boolean = false,
) {
    val c = Friday.colors
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val reduce = Friday.reduceMotion
    var completing by remember(done) { mutableStateOf(false) }
    val shownDone = done || completing
    val checkLabel = stringResource(if (done) R.string.reopen_task else R.string.complete_task)
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 4.dp, end = 16.dp)
            .heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckCircle(shownDone, description = checkLabel, onToggle = {
            if (done) onToggle()
            else if (!completing) {
                completing = true
                haptics.confirm()
                scope.launch { delay(if (reduce) 0 else 420); onToggle() }
            }
        })
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (shownDone) c.textTertiary else c.text,
                textDecoration = if (done) TextDecoration.LineThrough else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta != null) Text(meta, style = MaterialTheme.typography.bodySmall, color = metaColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (important) Ic(R.drawable.ic_star_fill, tint = c.amber, size = 16.dp, description = stringResource(R.string.important))
    }
}

@Composable
fun CaptureBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onMic: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
) {
    val c = Friday.colors
    Row(
        modifier
            .fillMaxWidth()
            .shadow(if (c.isDark) 0.dp else 6.dp, RoundedCornerShape(26.dp), ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.10f))
            .clip(RoundedCornerShape(26.dp))
            .background(c.surface)
            .border(0.5.dp, c.separator, RoundedCornerShape(26.dp))
            .heightIn(min = 52.dp)
            .padding(start = 18.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 14.dp)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (value.isNotBlank()) onSubmit() }),
            maxLines = 4,
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = c.textTertiary, maxLines = 1)
                    inner()
                }
            },
        )
        if (value.isBlank() && onMic != null) {
            RoundIconButton(R.drawable.ic_mic, stringResource(R.string.speak), onMic)
        } else {
            RoundIconButton(R.drawable.ic_arrow_upward, stringResource(R.string.send), onSubmit, filled = true, enabled = value.isNotBlank(), size = 36.dp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FridaySheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = Friday.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.background,
        contentColor = c.text,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        content = content,
    )
}

/** Small uppercase label above a card, e.g. "NOW". */
@Composable
fun Eyebrow(text: String, color: Color = Friday.colors.accent, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color, modifier = modifier)
}

@Composable
fun Card(modifier: Modifier = Modifier, padding: Dp = 20.dp, content: @Composable ColumnScope.() -> Unit) {
    val c = Friday.colors
    Column(
        modifier
            .fillMaxWidth()
            .shadow(if (c.isDark) 0.dp else 2.dp, RoundedCornerShape(20.dp), ambientColor = Color.Black.copy(alpha = 0.05f), spotColor = Color.Black.copy(alpha = 0.06f))
            .clip(RoundedCornerShape(20.dp))
            .background(c.surface)
            .padding(padding),
        content = content,
    )
}
