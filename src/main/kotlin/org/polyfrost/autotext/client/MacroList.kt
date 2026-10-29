@file:OptIn(ExperimentalOneConfigUiApi::class)

package org.polyfrost.autotext.client

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.DefaultContextMenuRepresentation
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import org.polyfrost.autotext.core.MacroDelivery
import org.polyfrost.oneconfig.api.config.v1.Property
import org.polyfrost.oneconfig.api.config.v1.Visualizer
import org.polyfrost.oneconfig.api.ui.v1.ExperimentalOneConfigUiApi
import org.polyfrost.oneconfig.api.ui.v1.SettingRowDecorator
import org.polyfrost.oneconfig.api.ui.v1.components.Icon
import org.polyfrost.oneconfig.api.ui.v1.components.IconButton
import org.polyfrost.oneconfig.api.ui.v1.components.Text
import org.polyfrost.oneconfig.api.ui.v1.components.Tooltip
import org.polyfrost.oneconfig.api.ui.v1.components.onClick
import org.polyfrost.oneconfig.api.ui.v1.components.rememberInteractionSource
import org.polyfrost.oneconfig.api.ui.v1.keybind.OneConfigKeybind
import org.polyfrost.oneconfig.api.ui.v1.theme.OneConfigTheme
import org.polyfrost.oneconfig.api.ui.v1.theme.concentric
import kotlin.math.roundToInt

internal class MacroRow(
    val id: String,
    val enabledState: MutableState<Boolean>,
    val enabled: Property<Boolean>,
    val text: Property<String>,
    val delivery: Property<MacroDelivery>,
    val keybind: Property<OneConfigKeybind>,
)

internal object MacroList : SettingRowDecorator() {
    const val ROWS = "autotext_macro_rows"
    private val ROW_HEIGHT = 52.dp
    private val ROW_GAP = 6.dp
    private val DELETE = Color(0xFFDD5B62)
    private val DELETE_HOVER = Color(0xFFFF6B73)

    val visualizer = Visualizer { prop -> prop.rows()?.let { Content(it) } }

    @Composable
    override fun Decorate(property: Property<*>, content: @Composable () -> Unit) {
        val rows = property.rows() ?: return content()
        val theme = OneConfigTheme.current
        val shape = theme.modCardShape
        Box(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(theme.modCardBackground, shape)
                .border(1.dp, Brush.verticalGradient(listOf(theme.borderColor, theme.borderColor.copy(0f))), shape)
                .padding(16.dp)
        ) {
            Content(rows)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun Property<*>.rows(): List<MacroRow>? = getMetadata<Any?>(ROWS) as? List<MacroRow>

    @Composable
    private fun Content(rows: List<MacroRow>) {
        val theme = OneConfigTheme.current
        val canAdd = rows.size < AutoTextConfig.MAX_MACROS
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Macros", Modifier, theme.textColor, 15.sp, FontWeight.Medium)
                    Text(
                        "Each macro sends a chat message, or runs a command if it starts with /.",
                        Modifier, theme.textColorSecondary, 13.sp,
                    )
                }
                Text("${rows.size} / ${AutoTextConfig.MAX_MACROS}", Modifier, theme.textColorSecondary, 13.sp)
                AddButton(canAdd)
            }
            if (rows.isEmpty()) EmptyState() else ReorderableRows(rows, canAdd)
        }
    }

    @Composable
    private fun AddButton(enabled: Boolean) {
        val theme = OneConfigTheme.current
        val accent = OneConfigTheme.accent
        val interaction = rememberInteractionSource()
        val hovered by interaction.collectIsHoveredAsState()
        val background by animateColorAsState(
            when {
                !enabled -> theme.componentBackground
                hovered -> accent.copy(alpha = 0.85f)
                else -> accent
            }
        )
        val foreground = if (enabled) theme.accentTextColor else theme.textColorSecondary
        val button = @Composable {
            Row(
                Modifier
                    .height(32.dp)
                    .clip(theme.buttonShape)
                    .background(background, theme.buttonShape)
                    .hoverable(interaction)
                    .onClick(interaction, enabled) {
                        if (AutoTextConfig.addMacro() != null) MacroEditorTree.syncRegistry()
                    }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon("plus", foreground, Modifier.size(14.dp))
                Text("New", Modifier, foreground, 13.sp, FontWeight.Medium)
            }
        }
        if (enabled) {
            button()
        } else {
            Tooltip(
                text = { Text("Delete a macro to add another.", Modifier, theme.textColor, 12.sp) },
                modifier = Modifier.widthIn(max = 200.dp),
                anchor = Alignment.TopCenter,
                content = button,
            )
        }
    }

    @Composable
    private fun EmptyState() {
        val theme = OneConfigTheme.current
        val shape = theme.modCardShape.concentric(16.dp, 8.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .border(1.dp, theme.borderColor, shape)
                .padding(vertical = 24.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("No macros yet", Modifier, theme.textColor, 14.sp, FontWeight.Medium)
            Text(
                "Add one to send a message or command with a single key press.",
                Modifier, theme.textColorSecondary, 13.sp,
            )
        }
    }

    @Composable
    private fun ReorderableRows(rows: List<MacroRow>, canDuplicate: Boolean) {
        val stride = ROW_HEIGHT + ROW_GAP
        val stridePx = with(LocalDensity.current) { stride.toPx() }
        var draggingIndex by remember(rows) { mutableIntStateOf(-1) }
        var dragAccum by remember(rows) { mutableFloatStateOf(0f) }
        val dragState = rememberDraggableState { dragAccum += it }
        fun dropIndex() = (draggingIndex + (dragAccum / stridePx).roundToInt()).coerceIn(0, rows.lastIndex)
        val targetIndex = if (draggingIndex < 0) -1 else dropIndex()

        Box(Modifier.fillMaxWidth().height(stride * rows.size - ROW_GAP)) {
            rows.forEachIndexed { index, row ->
                key(row.id) {
                    val dragging = index == draggingIndex
                    val base = index * stridePx
                    val target = when {
                        dragging -> base + dragAccum
                        draggingIndex < 0 -> base
                        targetIndex > draggingIndex && index in draggingIndex + 1..targetIndex -> base - stridePx
                        targetIndex < draggingIndex && index in targetIndex until draggingIndex -> base + stridePx
                        else -> base
                    }
                    val animated by animateFloatAsState(target)
                    val offset = if (dragging) target else animated
                    MacroRowView(
                        row,
                        dragging,
                        canDuplicate,
                        Modifier
                            .offset { IntOffset(0, offset.roundToInt()) }
                            .zIndex(if (dragging) 1f else 0f),
                        Modifier.draggable(
                            dragState,
                            Orientation.Vertical,
                            onDragStarted = {
                                draggingIndex = index
                                dragAccum = 0f
                            },
                            onDragStopped = {
                                val to = dropIndex()
                                draggingIndex = -1
                                dragAccum = 0f
                                if (to != index) {
                                    AutoTextConfig.moveMacro(row.id, to - index)
                                    MacroEditorTree.syncRegistry()
                                }
                            },
                        ),
                    )
                }
            }
        }
    }

    @Composable
    private fun MacroRowView(
        row: MacroRow,
        dragging: Boolean,
        canDuplicate: Boolean,
        modifier: Modifier,
        handle: Modifier,
    ) {
        val theme = OneConfigTheme.current
        val shape = theme.modCardShape.concentric(16.dp, 8.dp)
        val interaction = rememberInteractionSource()
        val hovered by interaction.collectIsHoveredAsState()
        val background by animateColorAsState(
            when {
                dragging -> theme.popupBackground
                hovered -> theme.textColor.copy(alpha = 0.05f)
                else -> theme.textColor.copy(alpha = 0.02f)
            }
        )
        val border by animateColorAsState(if (dragging) OneConfigTheme.accent else theme.borderColor)
        val fieldsAlpha by animateFloatAsState(if (row.enabledState.value) 1f else 0.55f)

        Row(
            modifier
                .fillMaxWidth()
                .height(ROW_HEIGHT)
                .clip(shape)
                .background(background, shape)
                .border(1.dp, border, shape)
                .hoverable(interaction)
                .padding(start = 6.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand).then(handle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    "dots-grid",
                    if (dragging || hovered) theme.textColor else theme.textColorSecondary,
                    Modifier.size(16.dp),
                )
            }
            Embedded(row.enabled)
            Row(
                Modifier.weight(1f).alpha(fieldsAlpha),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Embedded(row.text, Modifier.weight(1f))
                Embedded(row.delivery, Modifier.width(170.dp))
                Embedded(row.keybind, Modifier.width(150.dp))
            }
            RowAction("copy", "Duplicate", canDuplicate) {
                if (AutoTextConfig.duplicateMacro(row.id) != null) MacroEditorTree.syncRegistry()
            }
            RowAction("trash", "Delete", true, DELETE, DELETE_HOVER) {
                if (AutoTextConfig.deleteMacro(row.id)) MacroEditorTree.syncRegistry()
            }
        }
    }

    @Composable
    private fun Embedded(prop: Property<*>, modifier: Modifier = Modifier) {
        val visualizer = remember(prop) {
            (prop.getMetadata<Any?>("visualizer") as? Class<*>)
                ?.let { @Suppress("UNCHECKED_CAST") Visualizer[it as Class<out Visualizer>] }
        }
        val theme = OneConfigTheme.current
        val menu = remember(theme) {
            DefaultContextMenuRepresentation(
                backgroundColor = theme.popupBackground,
                textColor = theme.textColor,
                itemHoverColor = theme.textColor.copy(alpha = 0.10f),
                disabledTextColor = theme.textColorSecondary,
            )
        }
        CompositionLocalProvider(LocalContextMenuRepresentation provides menu) {
            Box(modifier, contentAlignment = Alignment.CenterStart, propagateMinConstraints = true) {
                visualizer?.visualize(prop)
            }
        }
    }

    @Composable
    private fun RowAction(
        icon: String,
        label: String,
        enabled: Boolean,
        foreground: Color = OneConfigTheme.current.textColor.copy(alpha = 0.7f),
        hoveredForeground: Color = OneConfigTheme.current.textColor,
        onClick: () -> Unit,
    ) {
        Tooltip(
            text = { Text(label, Modifier, OneConfigTheme.current.textColor, 12.sp) },
            modifier = Modifier.widthIn(max = 160.dp),
            anchor = Alignment.TopCenter,
        ) {
            if (enabled) {
                IconButton(icon, Modifier.size(18.dp), foreground, hoveredForeground, true, false, onClick)
            } else {
                Icon(icon, OneConfigTheme.current.textColor.copy(alpha = 0.2f), Modifier.size(18.dp))
            }
        }
    }
}
