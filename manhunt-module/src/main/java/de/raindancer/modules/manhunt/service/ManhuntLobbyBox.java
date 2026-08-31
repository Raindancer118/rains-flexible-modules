package de.raindancer.modules.manhunt.service;

import de.raindancer.modules.manhunt.ManhuntSettings;

import java.util.Objects;
import java.util.Optional;

/**
 * The waiting lobby's geometry: a cube around a configured spawn point, in one world. Bukkit-free,
 * exactly like {@code hungergames-module}'s own {@code LobbyBoxService} — containment is ordinary
 * arithmetic against a few numbers and a world name, so it is tested without a server (see
 * {@code ManhuntLobbyBoxTest}) and a listener converts a real {@code Location} into a {@link Point}
 * only at the door.
 *
 * <h2>Why this never tracks "who is a lobby occupant"</h2>
 * The box does not remember anybody it has ever relocated or released — every question it answers is
 * re-derived from the current {@link ManhuntSettings} and whatever {@link Point} it is handed, the
 * same reasoning {@code LobbyBoxService} documents for its own source: a stateful "am I holding this
 * player" flag can drift from reality (a player teleported away by another plugin, a server restart
 * mid-hunt), while pure geometry against the live position can not. {@code ManhuntLobbyListener} asks
 * this class fresh questions on every event instead of consulting a cache.
 *
 * <h2>Cube, not sphere</h2>
 * {@link ManhuntSettings#lobbyRadius()} is documented as a half-width, not a distance, so containment
 * is three independent {@code abs(...) <= radius} checks rather than one Euclidean distance — simpler,
 * and it matches what "radius" means for every other block-aligned area this reactor already has.
 */
public final class ManhuntLobbyBox {

    /** A position, spelled out just enough to test containment — no Bukkit {@code World} needed. */
    public record Point(String worldName, double x, double y, double z) {
    }

    private volatile ManhuntSettings settings;

    public ManhuntLobbyBox(ManhuntSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /** Told the live settings whenever they change — wired the same way every other service in this
     *  module is told, via {@code SettingsStore.onChange}. */
    public void settings(ManhuntSettings fresh) {
        this.settings = fresh;
    }

    /** Whether an admin has actually placed a waiting lobby yet. */
    public boolean isActive() {
        return settings.lobbySpawnSet() && !settings.lobbyWorldName().isBlank();
    }

    /** Whether {@code location} falls inside the protected cube — regardless of {@link #isActive()}. */
    public boolean isInside(Point location) {
        if (location == null || !isActive() || !settings.lobbyWorldName().equals(location.worldName())) {
            return false;
        }
        int radius = settings.lobbyRadius();
        return Math.abs(location.x() - settings.lobbyX()) <= radius
                && Math.abs(location.y() - settings.lobbyY()) <= radius
                && Math.abs(location.z() - settings.lobbyZ()) <= radius;
    }

    /** Where a relocated player should land — empty when there is nothing configured yet. */
    public Optional<Point> spawnPoint() {
        if (!isActive()) {
            return Optional.empty();
        }
        return Optional.of(new Point(settings.lobbyWorldName(), settings.lobbyX(), settings.lobbyY(),
                settings.lobbyZ()));
    }

    /** The direction a relocated player should land facing. */
    public double spawnYaw() {
        return settings.lobbyYaw();
    }

    /** Whether a hit between these two locations must be cancelled: the box is up and either party is
     *  in it — mirroring {@code LobbyBoxService.forbidsCombatBetween} exactly. */
    public boolean forbidsCombatBetween(Point attacker, Point victim) {
        return isActive() && (isInside(attacker) || isInside(victim));
    }
}
