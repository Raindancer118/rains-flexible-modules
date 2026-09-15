package de.raindancer.modules.manhunt.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.manhunt.model.Hunt;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.UUID;

/**
 * What being caught actually does to a Runner: they watch the rest of the hunt, and they are put back
 * afterwards.
 *
 * <h2>Why the mark is written on the player</h2>
 * Spectator is a game mode, and a game mode outlives everything this plugin knows about — a restart
 * mid-hunt, a crash, a player who logs out while dead. Without a mark, the only record that somebody
 * was made a spectator by a hunt lives in memory that a restart throws away, and the player comes back
 * to a world they can fly through and never touch again, with nothing able to tell them apart from
 * somebody staff put in spectator on purpose. The mark is on the player's own
 * {@code PersistentDataContainer}, which the server saves with them, so the restore survives whatever
 * the hunt did not.
 *
 * <h2>Why the restore teleports</h2>
 * A spectator drifts: through walls, under the world, a thousand blocks up. Handing them survival
 * where they happen to be floating is how somebody ends a hunt inside bedrock. Their own respawn point
 * — which the lobby set to where the run began — is a place that was walkable a moment ago.
 */
public final class Eliminations {

    private static final String MARK = "eliminated";

    private final Plugin plugin;
    private final NamespacedKey marker;

    public Eliminations(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.marker = new NamespacedKey(plugin, "manhunt-eliminated");
    }

    /** Out of the hunt: watching, and marked so the watching can be undone later. */
    public void spectate(Player runner) {
        runner.getPersistentDataContainer().set(marker, PersistentDataType.STRING, MARK);
        runner.setGameMode(GameMode.SPECTATOR);
    }

    /** Whether {@code player} is a Runner this module put into spectator and has not put back. */
    public boolean isMarked(Player player) {
        return player.getPersistentDataContainer().has(marker, PersistentDataType.STRING);
    }

    /**
     * Back to playing, wherever they can actually stand. A no-op for somebody carrying no mark, so
     * this can be called on anybody without touching a spectator who was never in a hunt.
     */
    public void restore(Player player) {
        if (!isMarked(player)) {
            return;
        }
        player.getPersistentDataContainer().remove(marker);
        if (player.getGameMode() == GameMode.SPECTATOR) {
            player.setGameMode(GameMode.SURVIVAL);
        }
        player.teleportAsync(player.getRespawnLocation() != null
                ? player.getRespawnLocation()
                : player.getWorld().getSpawnLocation());
    }

    /** Everybody the Hunters caught, put back — the hunt is over. */
    public void restoreAll(Hunt hunt) {
        for (UUID id : hunt.eliminated()) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) {
                // Folia: a hunt finishes on whatever thread the last death landed on, which is not
                // necessarily the one owning each of the others.
                Scheduling.entity(plugin, player, () -> restore(player));
            }
        }
    }
}
