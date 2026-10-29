package org.polyfrost.autotext.core

class MacroExecutor(
    private val target: MacroDispatchTarget,
    private val clock: MonotonicClock = MonotonicClock { System.nanoTime() / 1_000_000L },
) {
    private var lastDispatchAt = Long.MIN_VALUE
    private var burstCount = 0
    private var cooldownUntil = Long.MIN_VALUE
    private var actionCompletedThisTick = false

    fun trigger(
        macro: MacroDefinition,
        context: MacroContext,
        globallyEnabled: Boolean,
        conflictingBind: Boolean,
    ): TriggerResult {
        if (!globallyEnabled || !macro.enabled) return TriggerResult.DISABLED
        if (conflictingBind) return TriggerResult.CONFLICTING_BIND
        if (!context.playerReady || !context.connectionReady) return TriggerResult.NOT_IN_GAME
        if (context.screenOpen) return TriggerResult.SCREEN_OPEN
        if (actionCompletedThisTick) return TriggerResult.TICK_LIMIT

        val parsed = MacroPayloadParser.parse(macro.text)
        if (parsed !is PayloadValidation.Valid) return TriggerResult.INVALID

        val now = clock.nowMillis()
        if (now < cooldownUntil) return TriggerResult.COOLDOWN
        if (macro.delivery == MacroDelivery.SEND && context.hypixel && ServerPolicy.sendsChat(parsed.payload)) return TriggerResult.BLOCKED_SERVER

        val result = when (macro.delivery) {
            MacroDelivery.PREFILL -> {
                target.prefillChat(parsed.payload.chatInput)
                TriggerResult.PREFILLED
            }
            MacroDelivery.SEND -> {
                when (val payload = parsed.payload) {
                    is MacroPayload.Message -> target.sendMessage(payload.value)
                    is MacroPayload.Command -> target.sendCommand(payload.value)
                }
                TriggerResult.DISPATCHED
            }
        }
        burstCount = if (lastDispatchAt != Long.MIN_VALUE && now - lastDispatchAt < BURST_GAP_MILLIS) burstCount + 1 else 1
        lastDispatchAt = now
        if (burstCount >= BURST_LIMIT) {
            cooldownUntil = now + BURST_COOLDOWN_MILLIS
            burstCount = 0
        }
        actionCompletedThisTick = true
        return result
    }

    fun beginTick() {
        actionCompletedThisTick = false
    }

    fun clearSession() {
        lastDispatchAt = Long.MIN_VALUE
        burstCount = 0
        cooldownUntil = Long.MIN_VALUE
        actionCompletedThisTick = false
    }

    private companion object {
        const val BURST_LIMIT = 3
        const val BURST_GAP_MILLIS = 1000L
        const val BURST_COOLDOWN_MILLIS = 1500L
    }
}
