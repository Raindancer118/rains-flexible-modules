package de.raindancer.modules.manhunt.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.manhunt.ManhuntSettings;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watching a hunt without being in it: a third thing to be, on neither side.
 *
 * <h2>Why this is not a third team</h2>
 * {@code ManhuntTeams} is a two-team match on purpose — a third team would be a side, with a colour,
 * that every roster count, win condition and end-of-run sweep would then have to learn to ignore. A
 * spectator is the absence of a side, so it is a set of ids and a game mode, and every one of those
 * places keeps working without knowing this class exists.
 *
 * <h2>What is remembered, and given back</h2>
 * The game mode somebody was in when they started watching, so leaving puts them back in it rather
 * than in whatever this module guessed. Kept per player and cleared on the way out, which also means
 * a player who logs out while watching is put back the moment they ask to stop — the record outlives
 * the session on purpose, since a disconnect mid-hunt is not a decision to stop watching.
 */
public final class ManhuntSpectators {

    /** What each watcher was doing before they started, so it can be handed back. */
    private final Map<UUID, GameMode> before = new ConcurrentHashMap<>();

    private final Plugin plugin;
    private final ManhuntService manhunt;

    private volatile ManhuntSettings settings;

    public ManhuntSpectators(Plugin plugin, ManhuntService manhunt, ManhuntSettings settings) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.manhunt = Objects.requireNonNull(manhunt, "manhunt");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /** Told the live settings whenever they change — wired via {@code SettingsStore.onChange}. */
    public void settings(ManhuntSettings fresh) {
        this.settings = fresh;
    }

    /** What {@link #watch} answered. */
    public enum Result { WATCHING, STOPPED, NOT_ALLOWED, ON_A_SIDE }

    /** Whether spectating is offered at all right now. */
    public boolean isAllowed() {
        return settings.spectatorsAllowed();
    }

    public boolean isWatching(UUID player) {
        return before.containsKey(player);
    }

    public Set<UUID> watchers() {
        return Set.copyOf(before.keySet());
    }

    /** Starts or stops {@code player} watching — the same command both ways, like a light switch. */
    public Result watch(Player player) {
        UUID id = player.getUniqueId();
        if (isWatching(id)) {
            stop(player);
            return Result.STOPPED;
        }
        if (!settings.spectatorsAllowed()) {
            return Result.NOT_ALLOWED;
        }
        if (manhunt.teams().isRunner(id) || manhunt.teams().isHunter(id)) {
            return Result.ON_A_SIDE;
        }
        before.put(id, player.getGameMode());
        Scheduling.entity(plugin, player, () -> player.setGameMode(GameMode.SPECTATOR));
        return Result.WATCHING;
    }

    /** Puts {@code player} back in whatever they were in before they started watching. */
    public void stop(Player player) {
        GameMode was = before.remove(player.getUniqueId());
        if (was != null) {
            Scheduling.entity(plugin, player, () -> player.setGameMode(was));
        }
    }

    /** The hunt is over: everybody watching it is put back. */
    public void releaseAll() {
        for (UUID id : Set.copyOf(before.keySet())) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) {
                stop(player);
            }
        }
    }

    public String describe() {
        return "watching a hunt from outside it, and being put back afterwards";
    }
}
