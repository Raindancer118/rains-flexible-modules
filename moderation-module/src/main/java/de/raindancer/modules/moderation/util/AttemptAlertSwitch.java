package de.raindancer.modules.moderation.util;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Whether a member of staff wants to be told about punished players' attempts as they happen. On unless they
 * switched it off; kept on the player, so it follows them across restarts. Touch only on the player's own thread.
 */
public final class AttemptAlertSwitch {

    private final NamespacedKey key;

    public AttemptAlertSwitch(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "attempt-alerts-off");
    }

    public boolean isOn(Player player) {
        return !player.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    public void set(Player player, boolean on) {
        if (on) {
            player.getPersistentDataContainer().remove(key);
        } else {
            player.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        }
    }
}
