package de.raindancer.modules.playerutils.store;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Where a spectator goes back to, and as what — written on the spectator themselves before they leave, so a
 * crash or a relog mid-watch still puts them back where they were rather than leaving them a ghost.
 */
public final class SpectateReturns {

    private static final NamespacedKey RETURN = new NamespacedKey("rainsplayerutils", "spectate-return");

    /** Where to, as what, and whom they were watching. */
    public record Return(String world, double x, double y, double z, float yaw, float pitch, GameMode mode,
                         UUID watching) {

        public Optional<Location> location() {
            World found = Bukkit.getWorld(world);
            return found == null ? Optional.empty() : Optional.of(new Location(found, x, y, z, yaw, pitch));
        }

        String encode() {
            return String.join(";", world, String.valueOf(x), String.valueOf(y), String.valueOf(z),
                    String.valueOf(yaw), String.valueOf(pitch), mode.name(), watching.toString());
        }

        static Optional<Return> decode(String raw) {
            if (raw == null) {
                return Optional.empty();
            }
            String[] parts = raw.split(";");
            if (parts.length != 8) {
                return Optional.empty();
            }
            try {
                return Optional.of(new Return(parts[0], Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                        Double.parseDouble(parts[3]), Float.parseFloat(parts[4]), Float.parseFloat(parts[5]),
                        GameMode.valueOf(parts[6].toUpperCase(Locale.ROOT)), UUID.fromString(parts[7])));
            } catch (IllegalArgumentException broken) {
                return Optional.empty();
            }
        }
    }

    private SpectateReturns() {
    }

    public static void remember(Player spectator, Return back) {
        spectator.getPersistentDataContainer().set(RETURN, PersistentDataType.STRING, back.encode());
    }

    public static Optional<Return> of(Player spectator) {
        return Return.decode(spectator.getPersistentDataContainer().get(RETURN, PersistentDataType.STRING));
    }

    public static void forget(Player spectator) {
        spectator.getPersistentDataContainer().remove(RETURN);
    }

    public static Return from(Player spectator, UUID watching) {
        Location at = spectator.getLocation();
        return new Return(at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch(),
                spectator.getGameMode() == GameMode.SPECTATOR ? GameMode.SURVIVAL : spectator.getGameMode(),
                watching);
    }
}
