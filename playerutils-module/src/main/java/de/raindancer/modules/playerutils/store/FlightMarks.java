package de.raindancer.modules.playerutils.store;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

/**
 * Whether somebody was given flight, kept on the player themselves so it survives a restart, a crash
 * and a server move with their data file — and needs no file of ours to fall out of step with it.
 */
public final class FlightMarks {

    private static final NamespacedKey GRANTED = new NamespacedKey("rainsplayerutils", "flight");

    private FlightMarks() {
    }

    public static boolean isGranted(Player player) {
        return player.getPersistentDataContainer().has(GRANTED, PersistentDataType.BYTE);
    }

    public static void grant(Player player, boolean granted) {
        if (granted) {
            player.getPersistentDataContainer().set(GRANTED, PersistentDataType.BYTE, (byte) 1);
        } else {
            player.getPersistentDataContainer().remove(GRANTED);
        }
    }
}
