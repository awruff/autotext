package org.polyfrost.autotext.client

import java.util.concurrent.atomic.AtomicBoolean
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import org.polyfrost.autotext.core.*
import org.polyfrost.oneconfig.api.event.v1.eventHandler
import org.polyfrost.oneconfig.api.event.v1.events.MouseInputEvent
import org.polyfrost.oneconfig.api.notifications.v1.Notifications
import org.polyfrost.oneconfig.api.platform.v1.Platform
import org.polyfrost.oneconfig.api.ui.v1.keybind.KeybindManager
import org.polyfrost.oneconfig.api.ui.v1.keybind.OneConfigKeybind

object AutoTextRuntime {
    private val executor = MacroExecutor(MinecraftMacroTarget)
    private val registered = LinkedHashMap<String, OneConfigKeybind>()
    private var conflictingIds: Set<String> = emptySet()
    private val refreshQueued = AtomicBoolean(false)
    private var lastBlockedNoticeAt = 0L
    private var observedRevision = Long.MIN_VALUE
    private var registrationEpoch = 0L
    private var reconciledDownKeysCache: Set<Int>? = null
    private val pendingMousePresses = HashSet<Int>()
    private var tickMousePresses: Set<Int> = emptySet()

    private val modifierKeyCodes: Set<Int> by lazy {
        val keys = Platform.compatibility().keys()
        setOf(
            keys.keyLeftShift, keys.keyRightShift,
            keys.keyLeftControl, keys.keyRightControl,
            keys.keyLeftAlt, keys.keyRightAlt,
            keys.keyLeftSuper, keys.keyRightSuper,
        ).filterTo(LinkedHashSet()) { it >= 0 }
    }

    fun initialize() {
        eventHandler { (button, state): MouseInputEvent ->
            if (state == MouseInputEvent.PRESSED && Platform.screen().current<Any>() == null) {
                pendingMousePresses += button
            }
        }
        refreshBindings()
    }

    fun refreshBindings() {
        if (!isClientThread()) {
            if (refreshQueued.compareAndSet(false, true)) {
                runOnClientThread {
                    refreshQueued.set(false)
                    refreshBindingsNow()
                }
            }
        } else {
            refreshBindingsNow()
        }
    }

    fun conflicts(): Set<String> = conflictingIds

    fun syncIfChanged() {
        if (!isClientThread()) {
            if (refreshQueued.compareAndSet(false, true)) {
                runOnClientThread {
                    refreshQueued.set(false)
                    syncIfChangedNow()
                }
            }
        } else {
            syncIfChangedNow()
        }
    }

    fun clearSession() = executor.clearSession()

    fun beginTick() {
        reconciledDownKeysCache = null
        tickMousePresses = if (pendingMousePresses.isEmpty()) emptySet() else pendingMousePresses.toSet()
        pendingMousePresses.clear()
        executor.beginTick()
    }

    fun onProfileChanged() {
        val applyChange = {
            executor.clearSession()
            refreshBindingsNow()
        }
        if (isClientThread()) applyChange() else runOnClientThread(applyChange)
    }

    private fun refreshBindingsNow() {
        registrationEpoch++
        val epoch = registrationEpoch
        val state = AutoTextConfig.runtimeSnapshot()
        registered.values.forEach { KeybindManager.unregister(it) }
        registered.clear()
        val macros = state.macros
        conflictingIds = findConflicts(macros)
        macros.forEach { macro ->
            if (macro.enabled && macro.binding.isBound) {
                val bind = ExactModifierBind(
                    macro.binding.keys.clone(), macro.binding.mouseButtons.clone(),
                    macro.binding.modifiers, 0L,
                ) { pressed ->
                    if (pressed) trigger(macro.id, macro.binding.fingerprint(), epoch)
                    true
                }
                registered[macro.id] = KeybindManager.register(bind)
            }
        }
        observedRevision = state.revision
    }

    private fun trigger(id: String, expectedBinding: String, callbackEpoch: Long) {
        if (callbackEpoch != registrationEpoch) return
        if (AutoTextConfig.runtimeRevision() != observedRevision) {
            refreshBindingsNow()
            return
        }
        if (id in conflictingIds) {
            notifyBlocked(TriggerResult.CONFLICTING_BIND)
            return
        }
        val macro = AutoTextConfig.runtimeMacro(id) ?: return
        if (macro.binding.fingerprint() != expectedBinding) return
        if (callbackEpoch != registrationEpoch || AutoTextConfig.runtimeRevision() != observedRevision) {
            refreshBindingsNow()
            return
        }
        val client = Minecraft.getInstance()
        //? if = 1.8.9 {
        /*val connected = client.networkHandler != null
        val brand = client.player?.serverBrand
        *///?} else {
        val connected = client.connection != null
        val brand = client.connection?.serverBrand()
        //?}
        val context = MacroContext(
            client.player != null,
            connected,
            Platform.screen().current<Any>() != null,
            ServerPolicy.blocksImmediateSend(brand),
        )
        notifyBlocked(executor.trigger(macro, context, AutoTextConfig.enabled, id in conflictingIds))
    }

    private fun notifyBlocked(result: TriggerResult) {
        val message = when (result) {
            TriggerResult.BLOCKED_SERVER -> "Sending chat messages immediately is disabled on this server. Use Prefill instead."
            TriggerResult.INVALID -> "This macro has no valid message or command."
            TriggerResult.CONFLICTING_BIND -> "Two enabled macros use the same keybind."
            else -> return
        }
        val now = System.currentTimeMillis()
        if (now - lastBlockedNoticeAt < 2000L) return
        lastBlockedNoticeAt = now
        Notifications.info("Auto Text action blocked", message)
    }

    private fun findConflicts(macros: List<MacroDefinition>): Set<String> {
        val groups = LinkedHashMap<String, MutableList<String>>()
        macros.forEach { macro ->
            if (macro.enabled && macro.binding.isBound) {
                groups.getOrPut(macro.binding.fingerprint()) { ArrayList() }.add(macro.id)
            }
        }
        return groups.values.filter { it.size > 1 }.flatten().toSet()
    }

    private fun syncIfChangedNow() {
        if (AutoTextConfig.runtimeRevision() != observedRevision) {
            executor.clearSession()
            refreshBindingsNow()
        }
    }

    private fun reconciledDownKeys(trackedDownKeys: Set<Int>, trackedDownMouse: Set<Int>): Set<Int> {
        reconciledDownKeysCache?.let { return it }
        if (trackedDownKeys.isEmpty() && trackedDownMouse.isEmpty()) {
            return emptySet<Int>().also { reconciledDownKeysCache = it }
        }
        val physicalModifiersDown = modifierKeyCodes.filterTo(LinkedHashSet(), ::isKeyDown)
        return reconcilePhysicalModifiers(
            trackedDownKeys, trackedDownMouse, modifierKeyCodes, physicalModifiersDown,
        ).also { reconciledDownKeysCache = it }
    }

    private class ExactModifierBind(
        keyCodes: IntArray?, mouseBtns: IntArray?, mods: Byte, durationNanos: Long,
        action: (Boolean) -> Boolean,
    ) : OneConfigKeybind(keyCodes, mouseBtns, mods, durationNanos, action) {
        private val releaseGate = KeybindReleaseGate()

        override fun test(downKeys: Set<Int>, trackedDownMouse: Set<Int>, currentMods: Byte): Boolean {
            // OneConfig only checks binds once per tick, so a click released before the tick ends is never seen as down
            val downMouse = if (tickMousePresses.isEmpty()) trackedDownMouse else trackedDownMouse + tickMousePresses
            val actualDownKeys = reconciledDownKeys(downKeys, downMouse)
            val inputsDown = bindingInputsDown(keyCodes, mouseBtns, actualDownKeys, downMouse)
            val screenOpen = Platform.screen().current<Any>() != null
            val matches = bindingMatchesExactly(mods, keyCodes, mouseBtns, actualDownKeys, downMouse) && !screenOpen
            val blockCurrentPress = screenOpen || hasUnexpectedModifiers(mods, keyCodes, actualDownKeys)
            return releaseGate.allow(inputsDown, matches, blockCurrentPress)
        }

        override fun copyWith(keyCodes: IntArray?, mouseBtns: IntArray?, mods: Byte): OneConfigKeybind =
            ExactModifierBind(keyCodes, mouseBtns, mods, durationNanos, action).also {
                it.name = name
                it.category = category
                it.defaultKeybind = defaultKeybind
            }
    }

    private fun isClientThread(): Boolean =
        //? if = 1.8.9 {
        /*Minecraft.getInstance().isOnSameThread
        *///?} else
        Minecraft.getInstance().isSameThread

    private fun runOnClientThread(action: () -> Unit) {
        //? if = 1.8.9 {
        /*Minecraft.getInstance().executeTask(Runnable(action))
        *///?} else
        Minecraft.getInstance().execute(action)
    }

    private fun isKeyDown(key: Int): Boolean =
        //? if >= 26.3 || = 1.8.9 {
        /*InputConstants.isKeyDown(key)
        *///?} elif >= 1.21.10 {
        InputConstants.isKeyDown(Minecraft.getInstance().window, key)
        //?} else
        /*InputConstants.isKeyDown(Platform.compatibility().windowHandle(), key)*/
}
