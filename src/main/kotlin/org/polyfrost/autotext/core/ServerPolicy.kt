package org.polyfrost.autotext.core

import org.polyfrost.oneconfig.api.hypixel.v1.HypixelUtils

object ServerPolicy {
    private val chatCommands = setOf(
        "ac", "achat", "pc", "pchat", "gc", "gchat", "oc", "ochat", "cc", "coopchat",
        "msg", "message", "tell", "w", "whisper", "r", "reply", "shout",
    )
    private val chatSubcommands = mapOf(
        "p" to setOf("chat"),
        "party" to setOf("chat"),
        "g" to setOf("chat", "officer", "officerchat", "o"),
        "guild" to setOf("chat", "officer", "officerchat", "o"),
    )

    fun blocksImmediateSend(rawBrand: String?): Boolean =
        HypixelUtils.isHypixel() || rawBrand.isNullOrBlank()

    fun sendsChat(payload: MacroPayload): Boolean {
        if (payload !is MacroPayload.Command) return true
        val args = payload.value.lowercase().split(' ')
        val name = args[0].substringAfter(':')
        return name in chatCommands || args.getOrNull(1) in chatSubcommands[name].orEmpty()
    }
}
