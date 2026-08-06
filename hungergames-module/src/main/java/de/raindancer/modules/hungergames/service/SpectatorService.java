package de.raindancer.modules.hungergames.service;

import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.store.GameSession;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

/**
 * Eliminated tributes as real spectators: {@code GameMode.SPECTATOR} — no interacting, no collision,
 * invisible to the living, all Vanilla guarantees — plus a teleport restricted to living, online tributes.
 *
 * <h2>Why the restriction is enforced here, not trusted to whoever asks</h2>
 * Vanilla's own spectator-mode "fly to any entity" would let a spectator watch a tribute who has already
 * been eliminated (a corpse's camera, effectively) or one still in the lobby before the round exists at
 * all. {@link #teleportTo} is the one door: it asks {@link GameSession#participants()} whether the target
 * is alive and {@link OnlinePlayers} whether they are actually reachable, and a caller — command, menu, or
 * the admin HTTP endpoint — gets a plain {@code false} rather than a teleport to somewhere that makes no
 * sense.
 */
public final class SpectatorService implements IHungerGamesService, AdminEndpoints.Spectator {

    /** Resolving a UUID to an online {@link Player} — Bukkit's job, seamed so this class needs no server. */
    @FunctionalInterface
    public interface OnlinePlayers {
        Optional<Player> byUuid(UUID uuid);
    }

    /** Actually moving a spectator to stand where a target is — Bukkit's teleport, not this class's decision. */
    @FunctionalInterface
    public interface Teleport {
        void go(Player spectator, Player target);
    }

    /** Switching a freshly eliminated tribute into spectator mode and clearing whatever they were carrying. */
    @FunctionalInterface
    public interface SpectatorMode {
        void apply(Player player);
    }

    private final GameSession session;
    private final OnlinePlayers online;
    private final Teleport teleport;
    private final SpectatorMode spectatorMode;

    public SpectatorService(GameSession session, OnlinePlayers online, Teleport teleport,
                             SpectatorMode spectatorMode) {
        this.session = session;
        this.online = online;
        this.teleport = teleport;
        this.spectatorMode = spectatorMode;
    }

    /** Nothing here reads a setting — see {@link IHungerGamesService}'s note on implementing this empty. */
    @Override
    public void settings(HungerGamesSettings settings) {
        // intentionally empty
    }

    /**
     * Turns a freshly eliminated tribute into a spectator and points them at whoever makes the most sense
     * to watch first — see {@link #firstTarget}.
     */
    public void makeSpectator(Player player) {
        spectatorMode.apply(player);
        firstTarget(player.getUniqueId()).ifPresent(target -> teleportTo(player, target));
    }

    /**
     * Teleports a spectator to a target — only when the target is a living tribute who is actually online.
     *
     * @return whether the teleport happened
     */
    @Override
    public boolean teleportTo(Player spectator, UUID target) {
        if (!session.participants().isAlive(target)) {
            return false;
        }
        Optional<Player> targetPlayer = online.byUuid(target);
        if (targetPlayer.isEmpty()) {
            return false;
        }
        teleport.go(spectator, targetPlayer.get());
        return true;
    }

    /**
     * The tribute a fresh spectator is pointed at first: a living teammate who is online, if there is one,
     * otherwise any living tribute who is online, otherwise nobody.
     *
     * <p>Pure given {@link GameSession} and {@link OnlinePlayers} — the reason
     * {@code SpectatorServiceTest} can check this preference without a server: a teammate over a stranger
     * is what makes "you just died" land on somebody the spectator was actually playing with.
     */
    public Optional<UUID> firstTarget(UUID spectatorUuid) {
        Optional<UUID> teammate = session.teams().teamOf(spectatorUuid)
                .flatMap(team -> team.members().stream()
                        .filter(member -> !member.equals(spectatorUuid))
                        .filter(session.participants()::isAlive)
                        .filter(member -> online.byUuid(member).isPresent())
                        .findFirst());
        if (teammate.isPresent()) {
            return teammate;
        }
        // Excludes the spectator themselves — real usage always calls this the moment somebody is
        // eliminated, when they are already not "alive" any more and this filter is redundant, but a
        // defensive check that never points a spectator at themselves is one that also survives being
        // called a tick early, or from a test that has not modelled the elimination itself.
        return session.participants().alive().stream()
                .filter(uuid -> !uuid.equals(spectatorUuid))
                .filter(uuid -> online.byUuid(uuid).isPresent())
                .findFirst();
    }

    @Override
    public String describe() {
        return "eliminated tributes as real spectators";
    }
}
