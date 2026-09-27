package de.raindancer.modules.manhunt.tracker;

import de.raindancer.core.ui.profile.PlayerSwitch;
import de.raindancer.modules.manhunt.ManhuntSettings;
import org.bukkit.entity.Player;

/**
 * Whether one player sees the compass' particle trail: the server offers it
 * ({@link ManhuntSettings#trackerParticleTrail()}), and the player has not switched it off for
 * themselves. The server setting is the master switch — a player can opt out, never back in past it.
 */
public final class TrailPreference {

    /** Each player's own switch, kept with the player — on until they turn it off. */
    public static final PlayerSwitch SWITCH = new PlayerSwitch("manhunt", "particle-trail", true);

    /** What a toggle did. */
    public enum Toggled { ON, OFF, SERVER_OFF }

    private TrailPreference() {
    }

    public static boolean shows(Player player, ManhuntSettings settings) {
        return settings.trackerParticleTrail() && SWITCH.isOn(player);
    }

    public static Toggled toggle(Player player, ManhuntSettings settings) {
        if (!settings.trackerParticleTrail()) {
            return Toggled.SERVER_OFF;
        }
        return SWITCH.toggle(player) ? Toggled.ON : Toggled.OFF;
    }

    /** The message that says what a toggle did. */
    public static String messageKey(Toggled toggled) {
        return switch (toggled) {
            case ON -> "manhunt.trail.on";
            case OFF -> "manhunt.trail.off";
            case SERVER_OFF -> "manhunt.trail.server-off";
        };
    }
}
