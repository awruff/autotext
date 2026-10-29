package org.polyfrost.autotext.client

import net.minecraft.client.Minecraft
//? if = 1.8.9 {
/*import net.minecraft.client.gui.screen.game.ChatScreen
*///?} else
import net.minecraft.client.gui.screens.ChatScreen
import org.polyfrost.autotext.core.MacroDispatchTarget
import org.polyfrost.oneconfig.api.platform.v1.Platform

object MinecraftMacroTarget : MacroDispatchTarget {
    override fun sendMessage(message: String) {
        //? if = 1.8.9 {
        /*Minecraft.getInstance().player?.sendChat(message)
        *///?} else
        Minecraft.getInstance().connection?.sendChat(message)
    }

    override fun sendCommand(command: String) {
        //? if = 1.8.9 {
        /*Minecraft.getInstance().player?.sendChat("/$command")
        *///?} else
        Minecraft.getInstance().connection?.sendCommand(command)
    }

    override fun prefillChat(text: String) {
        //? if >= 1.21.10 {
        Platform.screen().display(ChatScreen(text, false), 0)
        //?} else
        /*Platform.screen().display(ChatScreen(text), 0)*/
    }
}
