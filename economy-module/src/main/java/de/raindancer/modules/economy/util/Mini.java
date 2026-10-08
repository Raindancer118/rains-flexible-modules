package de.raindancer.modules.economy.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

/** A component back as MiniMessage, for the menu icons that take markup — a painted amount in a lore line. */
public final class Mini {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private Mini() {
    }

    public static String of(Component component) {
        return MINI.serialize(component);
    }
}
