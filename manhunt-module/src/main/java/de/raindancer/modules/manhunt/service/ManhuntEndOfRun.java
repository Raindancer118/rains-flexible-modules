package de.raindancer.modules.manhunt.service;

import de.raindancer.modules.manhunt.ManhuntSettings;
import de.raindancer.modules.manhunt.model.ManhuntTeams;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The moment after a hunt: everybody out of Spectator, back in the waiting lobby if there is one,
 * and the two sides either kept for a rematch or emptied.
 *
 * <h2>Why this is not inside {@code ManhuntService}</h2>
 * The service runs a hunt; it deliberately knows nothing about the waiting lobby (see
 * {@link ManhuntLobbyListener}'s own note on there being no Bukkit event for joining a side, and the
 * lobby being wired from the outside). Hanging the homecoming off {@code onFinished} in
 * {@code ManhuntModule} keeps that true, and is the same "compose in the wiring class, not in the
 * service" reasoning the achievements and the tracking compass already get.
 *
 * <h2>Why Spectator is undone for everybody, not only for the eliminated</h2>
 * Three things in this module can leave somebody in Spectator — elimination, a Hunter's respawn
 * delay, and a delay whose run ended before it did. Asking which of the three applies to each player
 * would mean keeping a fourth record that can itself drift; putting every participant who is in
 * Spectator back into Survival at the one moment none of the three can still be legitimate is both
 * shorter and impossible to get wrong. A player who was already spectating for their own reasons
 * before the hunt is the one cost, and they were a participant in a hunt that has just ended.
 */
public final class ManhuntEndOfRun {

    private final Plugin plugin;
    private final ManhuntTeams teams;
    private final ManhuntLobbyListener lobby;

    private volatile ManhuntSettings settings;

    public ManhuntEndOfRun(Plugin plugin, ManhuntTeams teams, ManhuntLobbyListener lobby,
                          ManhuntSettings settings) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.teams = Objects.requireNonNull(teams, "teams");
        this.lobby = Objects.requireNonNull(lobby, "lobby");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /** Told the live settings whenever they change — wired via {@code SettingsStore.onChange}. */
    public void settings(ManhuntSettings fresh) {
        this.settings = fresh;
    }

    /**
     * Called once, with the roster the hunt ran with, the moment it is over.
     *
     * <p>The roster is passed in rather than read off {@link ManhuntTeams} because this may empty the
     * teams as its last act — reading them afterwards would find nobody to send home.
     */
    public void finish(Set<UUID> roster) {
        ManhuntSettings config = settings;
        for (UUID id : roster) {
            Player player = plugin.getServer().getPlayer(id);
            if (player == null) {
                continue;
            }
            if (player.getGameMode() == GameMode.SPECTATOR) {
                player.setGameMode(GameMode.SURVIVAL);
            }
            if (config.returnToLobbyOnFinish()) {
                // false: the hunt is over, so this is exactly the "not running" case the lobby
                // relocates for. It is a no-op when no lobby has been placed.
                lobby.relocateIfWaiting(player, false);
            }
        }
        if (!config.keepRosterOnFinish()) {
            for (UUID id : roster) {
                teams.leave(id);
            }
        }
    }

    public String describe() {
        return "the homecoming: out of Spectator, back to the lobby, and the sides kept or emptied";
    }
}
