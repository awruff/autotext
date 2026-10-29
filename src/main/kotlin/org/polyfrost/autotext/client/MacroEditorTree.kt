package org.polyfrost.autotext.client

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.ComponentLike
import org.polyfrost.autotext.core.MacroBinding
import org.polyfrost.autotext.core.MacroDefinition
import org.polyfrost.autotext.core.MacroDelivery
import org.polyfrost.oneconfig.api.config.v1.Properties
import org.polyfrost.oneconfig.api.config.v1.Property
import org.polyfrost.oneconfig.api.config.v1.Tree
import org.polyfrost.oneconfig.api.config.v1.Visualizer
import org.polyfrost.oneconfig.api.config.v1.Visualizer.DropdownVisualizer
import org.polyfrost.oneconfig.api.config.v1.Visualizer.KeybindVisualizer
import org.polyfrost.oneconfig.api.config.v1.Visualizer.SwitchVisualizer
import org.polyfrost.oneconfig.api.config.v1.Visualizer.TextVisualizer
import org.polyfrost.oneconfig.api.platform.v1.Platform
import org.polyfrost.oneconfig.api.ui.v1.keybind.OneConfigKeybind
import org.polyfrost.oneconfig.internal.ui.api.ConfigRegistry
import org.polyfrost.oneconfig.internal.ui.api.ConfigSource
import org.polyfrost.oneconfig.internal.ui.api.TreeConfigData
import org.polyfrost.oneconfig.utils.v1.dsl.openUI
import java.nio.charset.StandardCharsets
import java.util.UUID

internal object MacroEditorTree {
    private const val KEYBIND_CATEGORY = "autotext.macros"
    private const val NO_MC_MIRROR = "oc_no_mc_mirror"
    private var installedTree: Tree? = null
    private var installedRevision = Long.MIN_VALUE

    @Synchronized
    fun open() {
        currentTree()?.openUI()
    }

    @Synchronized
    fun syncRegistry() {
        val tree = currentTree() ?: return
        if (ConfigRegistry.findTree(AutoTextConfig.id) !== tree) {
            ConfigRegistry.register(TreeConfigData(tree, ConfigSource.OC))
        }
    }

    private fun currentTree(): Tree? {
        while (true) {
            val revision = AutoTextConfig.editorRevision()
            val current = installedTree
            if (current != null && installedRevision == revision) return current
            val rebuilt = create() ?: return null
            if (AutoTextConfig.editorRevision() != revision) continue
            installedTree = rebuilt
            installedRevision = revision
            return rebuilt
        }
    }

    internal fun create(): Tree? {
        val configTree = AutoTextConfig.tree ?: return null
        val root = Tree(AutoTextConfig.id, AutoTextConfig.title, null, null).apply {
            configTree.metadata?.let(::addMetadata)
        }
        AutoTextConfig.editorSettings(configTree).forEach { root.put(it) }

        val rows = AutoTextConfig.snapshot().map(::buildRow)
        rows.forEach { root.put(it.keybind) }
        root.put(
            Properties.dummy("macros", "autotext.macros", "autotext.macros.description")
                .metadata("visualizer", MacroList.visualizer)
                .metadata(MacroList.ROWS, rows)
        )
        return root
    }

    private fun buildRow(snapshot: MacroDefinition): MacroRow {
        val id = snapshot.id
        val liveKeybindTitle = LiveText(keybindPreview(snapshot.text))
        val enabledState = mutableStateOf(snapshot.enabled)
        var editorBind = snapshot.binding.toOneConfigKeybind()
        return MacroRow(
            id = id,
            enabledState = enabledState,
            enabled = functional(
                "enabled", null, null, Boolean::class.javaObjectType,
                getter = { current(id)?.enabled == true },
                setter = { enabled ->
                    enabledState.value = enabled
                    if (AutoTextConfig.editMacro(id) { it.enabled = enabled }) syncRegistry()
                },
                visualizer = SwitchVisualizer::class.java
            ),
            text = functional(
                "text", null, null,
                String::class.java,
                getter = { current(id)?.text ?: "" },
                setter = { value ->
                    liveKeybindTitle.value = keybindPreview(value)
                    if (AutoTextConfig.editMacro(id) { it.text = value }) syncRegistry()
                },
                visualizer = TextVisualizer::class.java
            ).metadata("placeholder", "autotext.macro.text.placeholder"),
            delivery = functional(
                "delivery", null, null, MacroDelivery::class.java,
                getter = { current(id)?.delivery ?: MacroDelivery.SEND },
                setter = { delivery ->
                    if (AutoTextConfig.editMacro(id) { it.delivery = delivery }) syncRegistry()
                },
                visualizer = DropdownVisualizer::class.java
            ).metadata("options", arrayOf("autotext.macro.delivery.send", "autotext.macro.delivery.prefill").map { Platform.i18n().translateString(it) }.toTypedArray()),
            keybind = functional(
                "keybind_${nodeId(id)}", liveKeybindTitle, "autotext.macro.keybind.description",
                OneConfigKeybind::class.java,
                getter = { editorBind },
                setter = { bind ->
                    editorBind = bind
                    if (AutoTextConfig.editMacro(id) { it.binding = bind.toMacroBinding() }) syncRegistry()
                },
                visualizer = KeybindVisualizer::class.java
            ).metadata(NO_MC_MIRROR, true)
                .metadata("hidden", true)
                .metadata("category", KEYBIND_CATEGORY)
                .metadata("subcategory", KEYBIND_CATEGORY),
        )
    }

    private fun current(id: String): MacroDefinition? = AutoTextConfig.macro(id)

    private fun <T> functional(
        id: String,
        title: Any? = null,
        description: String? = null,
        type: Class<T>,
        getter: () -> T,
        setter: (T) -> Unit,
        visualizer: Class<out Visualizer>? = null
    ): Property<T> = Properties.functional({ getter() }, { setter(it) }, id, title, description, type).apply {
        if (visualizer != null) addMetadata("visualizer", visualizer)
    }

    private fun <T : Property<*>> T.metadata(key: String, value: Any): T = apply { addMetadata(key, value) }

    private fun MacroBinding.toOneConfigKeybind(): OneConfigKeybind = OneConfigKeybind(
        keys.clone().takeIf { it.isNotEmpty() },
        mouseButtons.clone().takeIf { it.isNotEmpty() },
        modifiers,
        0L,
        { true }
    )

    private fun OneConfigKeybind.toMacroBinding(): MacroBinding = MacroBinding(
        keyCodes?.clone() ?: intArrayOf(),
        mouseBtns?.clone() ?: intArrayOf(),
        mods
    )

    private fun nodeId(id: String): String =
        "macro_${UUID.nameUUIDFromBytes(id.toByteArray(StandardCharsets.UTF_8)).toString().replace("-", "")}"

    private fun keybindPreview(text: String): String = Regex("\\s+").replace(text.trim(), " ").take(40)

    private class LiveText(initial: String) : ComponentLike {
        var value by mutableStateOf(initial)
        override fun asComponent(): Component = Component.translatable(
            "autotext.macro.keybind",
            if (value.isBlank()) Component.translatable("autotext.macro.untitled") else Component.text(value),
        )
    }
}
