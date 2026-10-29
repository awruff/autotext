package org.polyfrost.autotext.core

import java.util.UUID

class MacroDefinition(
    @JvmField var id: String = UUID.randomUUID().toString(),
    @JvmField var text: String = "",
    @JvmField var enabled: Boolean = true,
    @JvmField var delivery: MacroDelivery = MacroDelivery.SEND,
    @JvmField var binding: MacroBinding = MacroBinding(),
) {
    fun deepCopy(id: String = this.id): MacroDefinition =
        MacroDefinition(id, text, enabled, delivery, binding.deepCopy())
}
