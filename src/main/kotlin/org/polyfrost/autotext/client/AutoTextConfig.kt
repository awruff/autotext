package org.polyfrost.autotext.client

import java.util.UUID
import org.apache.logging.log4j.LogManager
import org.polyfrost.autotext.core.MacroBinding
import org.polyfrost.autotext.core.MacroCodec
import org.polyfrost.autotext.core.MacroDefinition
import org.polyfrost.oneconfig.api.config.v1.Config
import org.polyfrost.oneconfig.api.config.v1.ConfigManager
import org.polyfrost.oneconfig.api.config.v1.Properties
import org.polyfrost.oneconfig.api.config.v1.Property
import org.polyfrost.oneconfig.api.config.v1.Tree
import org.polyfrost.oneconfig.api.config.v1.Visualizer.SwitchVisualizer

object AutoTextConfig : Config(
    "autotext.json", "assets/autotext/autotext_dark.svg", "Auto Text", Category.QOL,
) {
    private val logger = LogManager.getLogger("AutoText/Config")

    @JvmField @Volatile
    var enabled = true

    @JvmField @Volatile
    var macrosJson = "{\"version\":1,\"macros\":[]}"

    @JvmField @Volatile
    var macrosBackupJson = ""

    private val payloadLock = Any()
    @Volatile private var payloadState = PayloadState()
    private var profileRebinding = false
    private var payloadCallbackProperty: Property<*>? = null
    @Volatile private var activePropertyOwner: Any? = null

    const val MAX_MACROS = 100

    override fun initialize(byConfigManager: Boolean) {
        if (byConfigManager) {
            synchronized(payloadLock) {
                activePropertyOwner = null
                profileRebinding = true
                resetBackingFieldsLocked()
            }
        }

        try {
            super.initialize(byConfigManager)
            synchronized(payloadLock) {
                loadPayloadLocked(macrosJson, true, byConfigManager || payloadCallbackProperty == null)
                profileRebinding = false
            }
            installPayloadCallback()
        } finally {
            if (byConfigManager) {
                synchronized(payloadLock) {
                    profileRebinding = false
                }
            }
        }
    }

    override fun makeTree(): Tree {
        val owner = Any()
        synchronized(payloadLock) {
            activePropertyOwner = owner
        }
        return Tree(id, null, null, null).apply {
            put(ownedProperty(
                owner, "enabled", "Enabled",
                "Turn all macros on or off. On Hypixel, chat messages and chat commands like /pc or /msg can only be prefilled.",
                Boolean::class.javaObjectType, { enabled }, { enabled = it },
            ).metadata("visualizer", SwitchVisualizer::class.java))
            put(ownedProperty(
                owner, "macrosJson", type = String::class.java,
                getter = { macrosJson }, setter = { macrosJson = it },
            ).metadata("hidden", true))
            put(ownedProperty(
                owner, "macrosBackupJson", type = String::class.java,
                getter = { macrosBackupJson }, setter = { macrosBackupJson = it },
            ).metadata("hidden", true))
        }
    }

    fun snapshot(): List<MacroDefinition> = synchronized(payloadLock) {
        syncExternallyChangedPayloadLocked()
        payloadState.macros.map { it.deepCopy() }
    }

    internal fun macro(id: String): MacroDefinition? = runtimeMacro(id)

    internal fun runtimeMacro(id: String): MacroDefinition? = synchronized(payloadLock) {
        syncExternallyChangedPayloadLocked()
        payloadState.byId[id]?.deepCopy()
    }

    internal fun runtimeSnapshot(): RuntimeSnapshot = synchronized(payloadLock) {
        syncExternallyChangedPayloadLocked()
        RuntimeSnapshot(payloadState.runtimeRevision, payloadState.macros.map { it.deepCopy() })
    }

    internal fun isMacroPayloadHealthy(): Boolean = synchronized(payloadLock) {
        syncExternallyChangedPayloadLocked()
        payloadState.healthy
    }

    internal fun editorSettings(expectedTree: Tree): List<Property<*>> =
        listOf("enabled").map { id ->
            requireNotNull(expectedTree.getProp(id)) { "AutoText config is missing property $id" }
        }

    internal fun editorRevision(): Long {
        syncExternallyChangedPayload()
        return payloadState.editorRevision
    }

    internal fun runtimeRevision(): Long {
        syncExternallyChangedPayload()
        return payloadState.runtimeRevision
    }

    fun replaceMacros(values: List<MacroDefinition>) {
        val publication = withConfigStateLocks {
            syncExternallyChangedPayloadLocked()
            if (!canMutateLocked()) null else publishLocked(normalize(values), true, true)
        }
        finishPublication(publication)
    }

    fun editMacro(id: String, update: (MacroDefinition) -> Unit): Boolean {
        val draft = synchronized(payloadLock) {
            syncExternallyChangedPayloadLocked()
            if (!canMutateLocked()) null else {
                payloadState.byId[id]?.deepCopy()?.let { EditDraft(payloadState.generation, it) }
            }
        } ?: return false

        update(draft.macro)
        var editorRebuild = false
        val publication = withConfigStateLocks {
            syncExternallyChangedPayloadLocked()
            val canCommit = acceptsEditGeneration(
                payloadState.generation, draft.generation, payloadState.healthy, profileRebinding,
            )
            if (!canCommit) return@withConfigStateLocks null
            val values = payloadState.macros.toMutableList()
            val index = values.indexOfFirst { it.id == id }
            if (index < 0) return@withConfigStateLocks null
            values[index] = draft.macro
            val normalized = normalize(values)
            editorRebuild = !sameMacroValue(draft.macro, normalized[index])
            publishLocked(normalized, false, editorRebuild)
        }
        finishPublication(publication)
        return publication != null && editorRebuild
    }

    fun addMacro(): MacroDefinition? {
        var added: MacroDefinition? = null
        val publication = withConfigStateLocks {
            syncExternallyChangedPayloadLocked()
            if (!canMutateLocked() || payloadState.macros.size >= MAX_MACROS) {
                return@withConfigStateLocks null
            }
            val macro = MacroDefinition()
            added = macro
            publishLocked(payloadState.macros + macro, true, true)
        }
        finishPublication(publication)
        return added
    }

    fun deleteMacro(id: String): Boolean {
        val publication = withConfigStateLocks {
            syncExternallyChangedPayloadLocked()
            if (!canMutateLocked() || !payloadState.byId.containsKey(id)) null
            else publishLocked(payloadState.macros.filterNot { it.id == id }, true, true)
        } ?: return false
        finishPublication(publication)
        return true
    }

    fun duplicateMacro(id: String): MacroDefinition? {
        var duplicated: MacroDefinition? = null
        val publication = withConfigStateLocks {
            syncExternallyChangedPayloadLocked()
            if (!canMutateLocked() || payloadState.macros.size >= MAX_MACROS) {
                return@withConfigStateLocks null
            }
            val index = payloadState.macros.indexOfFirst { it.id == id }
            if (index < 0) return@withConfigStateLocks null
            val source = payloadState.macros[index]
            val copy = source.deepCopy(UUID.randomUUID().toString()).apply {
                enabled = false
                binding = MacroBinding()
            }
            duplicated = copy
            publishLocked(payloadState.macros.toMutableList().apply { add(index + 1, copy) }, true, true)
        }
        finishPublication(publication)
        return duplicated
    }

    fun moveMacro(id: String, offset: Int) {
        val publication = withConfigStateLocks {
            syncExternallyChangedPayloadLocked()
            if (!canMutateLocked()) return@withConfigStateLocks null
            val values = payloadState.macros.toMutableList()
            val from = values.indexOfFirst { it.id == id }
            if (from < 0) return@withConfigStateLocks null
            val to = (from + offset).coerceIn(0, values.lastIndex)
            if (from == to) return@withConfigStateLocks null
            values.add(to, values.removeAt(from))
            publishLocked(values, true, true)
        }
        finishPublication(publication)
    }

    fun resetBrokenMacroPayload(): Boolean {
        val publication = withConfigStateLocks {
            syncExternallyChangedPayloadLocked()
            if (profileRebinding || payloadState.healthy) return@withConfigStateLocks null
            macrosBackupJson = macrosJson
            publishLocked(emptyList(), true, true)
        } ?: return false
        finishPublication(publication)
        return true
    }

    private fun publishLocked(
        values: List<MacroDefinition>, saveImmediately: Boolean, rebuildEditor: Boolean,
    ): Publication {
        val previous = payloadState
        val owned = values.map { it.deepCopy() }
        val encoded = MacroCodec.encode(owned)
        val runtimeChanged = !runtimeShapeEquals(previous.macros, owned)
        val structureChanged = !sameMacroOrder(previous.macros, owned)
        macrosJson = encoded
        payloadState = PayloadState(
            owned, owned.associateBy { it.id }, encoded, true,
            previous.generation + 1,
            previous.editorRevision + if (rebuildEditor || structureChanged || !previous.healthy) 1 else 0,
            previous.runtimeRevision + if (runtimeChanged) 1 else 0,
        )
        val token = if (saveImmediately) SaveToken(payloadState.generation, tree) else null
        return Publication(token, runtimeChanged)
    }

    private fun syncExternallyChangedPayload() {
        if (macrosJson !== payloadState.loadedPayload) {
            synchronized(payloadLock) { syncExternallyChangedPayloadLocked() }
        }
    }

    private fun syncExternallyChangedPayloadLocked(): Boolean {
        if (profileRebinding) return false
        val raw = macrosJson
        if (raw === payloadState.loadedPayload) return false
        if (raw == payloadState.loadedPayload) {
            payloadState = payloadState.copy(loadedPayload = raw)
            return false
        }
        return loadPayloadLocked(raw, false, false)
    }

    private fun loadPayloadLocked(raw: String, canonicalize: Boolean, forceRuntimeRevision: Boolean): Boolean {
        val previous = payloadState
        val decoded = MacroCodec.decode(raw)
        if (!decoded.valid) {
            val runtimeChanged = forceRuntimeRevision || previous.macros.isNotEmpty()
            payloadState = PayloadState(
                loadedPayload = raw, healthy = false,
                generation = previous.generation + 1,
                editorRevision = previous.editorRevision + 1,
                runtimeRevision = previous.runtimeRevision + if (runtimeChanged) 1 else 0,
            )
            logger.error("Could not read the saved macro list; keeping the original payload untouched")
            return runtimeChanged
        }

        val normalized = normalize(decoded.macros)
        val storedPayload = if (canonicalize) MacroCodec.encode(normalized).also { macrosJson = it } else raw
        val runtimeChanged = forceRuntimeRevision || !runtimeShapeEquals(previous.macros, normalized)
        payloadState = PayloadState(
            normalized, normalized.associateBy { it.id }, storedPayload, true,
            previous.generation + 1, previous.editorRevision + 1,
            previous.runtimeRevision + if (runtimeChanged) 1 else 0,
        )
        return runtimeChanged
    }

    private fun resetBackingFieldsLocked() {
        val previous = payloadState
        enabled = true
        macrosJson = "{\"version\":1,\"macros\":[]}"
        macrosBackupJson = ""
        payloadState = PayloadState(
            generation = previous.generation + 1,
            editorRevision = previous.editorRevision + 1,
            runtimeRevision = previous.runtimeRevision + 1,
        )
    }

    private fun normalize(values: List<MacroDefinition>): List<MacroDefinition> {
        val usedIds = HashSet<String>()
        return values.take(MAX_MACROS).map { source ->
            source.deepCopy().apply {
                id = id.takeIf { it.isNotBlank() && it.length <= 128 && usedIds.add(it) }
                    ?: generateSequence { UUID.randomUUID().toString() }.first { usedIds.add(it) }
                text = truncateUtf16Safely(text, 1024)
                binding = MacroBinding(
                    binding.keys.asSequence().filter { it in 1..512 }.distinct().take(16).toList().toIntArray(),
                    binding.mouseButtons.asSequence().filter { it in 0..31 }.distinct().take(16).toList().toIntArray(),
                    (binding.modifiers.toInt() and 15).toByte(),
                )
            }
        }
    }

    private fun canMutateLocked(): Boolean = !profileRebinding && payloadState.healthy

    private inline fun <T> withConfigStateLocks(action: () -> T): T =
        synchronized(ConfigManager::class.java) { synchronized(payloadLock) { action() } }

    private fun <T> ownedProperty(
        owner: Any, id: String, title: Any? = null, description: Any? = null,
        type: Class<T>, getter: () -> T, setter: (T) -> Unit,
    ): Property<T> {
        var ownedValue = synchronized(payloadLock) { getter() }
        return Properties.functional(
            {
                withConfigStateLocks {
                    if (activePropertyOwner === owner) getter().also { ownedValue = it } else ownedValue
                }
            },
            { value ->
                withConfigStateLocks {
                    if (activePropertyOwner === owner) {
                        setter(value)
                        ownedValue = value
                    }
                }
            },
            id, title, description, type,
        )
    }

    private fun <T : Property<*>> T.metadata(key: String, value: Any): T = apply {
        addMetadata(key, value)
    }

    private fun installPayloadCallback() {
        val ownerTree = tree ?: return
        val property = ownerTree.getProp("macrosJson") ?: return
        synchronized(payloadLock) {
            if (payloadCallbackProperty === property) return
            payloadCallbackProperty = property
            property.addCallback {
                val runtimeChanged = synchronized(payloadLock) {
                    if (payloadCallbackProperty === property && tree === ownerTree) {
                        syncExternallyChangedPayloadLocked()
                    } else false
                }
                if (runtimeChanged) AutoTextRuntime.syncIfChanged()
                false
            }
        }
    }

    private fun finishPublication(publication: Publication?) {
        if (publication == null) return
        if (publication.runtimeChanged) AutoTextRuntime.syncIfChanged()
        publication.saveToken?.let { saveIfCurrent(it) }
    }

    private fun saveIfCurrent(token: SaveToken) {
        val expectedTree = token.tree ?: return
        synchronized(ConfigManager::class.java) {
            val current = synchronized(payloadLock) {
                !profileRebinding && payloadState.generation == token.generation && tree === expectedTree
            }
            if (current) ConfigManager.active().save(expectedTree)
        }
    }

    private data class PayloadState(
        val macros: List<MacroDefinition> = emptyList(),
        val byId: Map<String, MacroDefinition> = emptyMap(),
        val loadedPayload: String = "{\"version\":1,\"macros\":[]}",
        val healthy: Boolean = true,
        val generation: Long = 0,
        val editorRevision: Long = 0,
        val runtimeRevision: Long = 0,
    )

    private data class SaveToken(val generation: Long, val tree: Tree?)
    private data class Publication(val saveToken: SaveToken?, val runtimeChanged: Boolean)
    private data class EditDraft(val generation: Long, val macro: MacroDefinition)
}

internal fun runtimeShapeEquals(first: List<MacroDefinition>, second: List<MacroDefinition>): Boolean {
    if (first.size != second.size) return false
    return first.indices.all { index ->
        val left = first[index]
        val right = second[index]
        left.id == right.id && left.enabled == right.enabled &&
            left.binding.modifiers == right.binding.modifiers &&
            left.binding.keys.contentEquals(right.binding.keys) &&
            left.binding.mouseButtons.contentEquals(right.binding.mouseButtons)
    }
}

private fun sameMacroOrder(first: List<MacroDefinition>, second: List<MacroDefinition>): Boolean =
    first.size == second.size && first.indices.all { first[it].id == second[it].id }

private fun sameMacroValue(first: MacroDefinition, second: MacroDefinition): Boolean =
    first.id == second.id && first.text == second.text &&
        first.enabled == second.enabled && first.delivery == second.delivery &&
        first.binding.modifiers == second.binding.modifiers &&
        first.binding.keys.contentEquals(second.binding.keys) &&
        first.binding.mouseButtons.contentEquals(second.binding.mouseButtons)

internal fun acceptsEditGeneration(
    currentGeneration: Long, expectedGeneration: Long, healthy: Boolean, profileRebinding: Boolean,
): Boolean = healthy && !profileRebinding && currentGeneration == expectedGeneration

internal fun truncateUtf16Safely(value: String, maxLength: Int): String {
    require(maxLength >= 0) { "maxLength must not be negative" }
    if (value.length <= maxLength) return value
    var end = maxLength
    if (end > 0 && Character.isHighSurrogate(value[end - 1]) && Character.isLowSurrogate(value[end])) end--
    return value.substring(0, end)
}
