package org.polyfrost.autotext.client

import org.polyfrost.oneconfig.api.commands.v1.CommandManager
import org.polyfrost.oneconfig.api.config.v1.ConfigManager
import org.polyfrost.oneconfig.api.event.v1.eventHandler
import org.polyfrost.oneconfig.api.event.v1.events.ServerJoinEvent
import org.polyfrost.oneconfig.api.event.v1.events.TickEvent
import org.polyfrost.oneconfig.api.ui.v1.ExperimentalOneConfigUiApi
import org.polyfrost.oneconfig.api.ui.v1.SettingRowDecorators

object AutoTextClient {
    private var initialized = false

    fun initialize() {
        if (initialized) return
        initialized = true
        AutoTextConfig.preload()
        @OptIn(ExperimentalOneConfigUiApi::class)
        SettingRowDecorators.register(MacroList)
        MacroEditorTree.syncRegistry()
        ConfigManager.addProfileChangeListener { AutoTextRuntime.onProfileChanged() }
        AutoTextRuntime.initialize()

        eventHandler { _: TickEvent.Start -> AutoTextRuntime.beginTick() }
        eventHandler { _: TickEvent.End ->
            AutoTextRuntime.syncIfChanged()
            MacroEditorTree.syncRegistry()
        }
        eventHandler { _: ServerJoinEvent -> AutoTextRuntime.clearSession() }

        CommandManager.register(CommandManager.literal("autotext").executes {
            MacroEditorTree.open()
            1
        })
    }
}
