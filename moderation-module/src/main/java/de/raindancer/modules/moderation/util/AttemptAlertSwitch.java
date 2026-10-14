package de.raindancer.modules.moderation.util;

import de.raindancer.core.ui.profile.PlayerSwitch;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Whether a member of staff wants to be told about punished players' attempts as they happen. On unless they
 * switched it off; a Core {@link PlayerSwitch}, so it follows them from server to server. Touch only on the
 * player's own thread.
 */
public final class AttemptAlertSwitch {

    public static final String NAME = "attempt-alerts";

    private static final PlayerSwitch SWITCH = new PlayerSwitch("moderation", NAME, true);

    /** Where "off" was kept before it was a Core switch: on the player, under this plugin's own namespace. */
    private final NamespacedKey legacyOff;

    public AttemptAlertSwitch(Plugin plugin) {
        this.legacyOff = new NamespacedKey(plugin, "attempt-alerts-off");
    }

    public boolean isOn(Player player) {
        if (player.getPersistentDataContainer().has(legacyOff, PersistentDataType.BYTE)) {
            return false;
        }
        return SWITCH.isOn(player);
    }

    public void set(Player player, boolean on) {
        player.getPersistentDataContainer().remove(legacyOff);
        SWITCH.set(player, on);
    }
}
