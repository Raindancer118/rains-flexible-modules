package de.raindancer.modules.hungergames.service;

import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.store.GameSession;

import java.util.Optional;
import java.util.UUID;

/**
 * The rules of the glass lobby that floats above the arena: who belongs inside it, and while it stands, that
 * nobody fights inside it.
 *
 * <h2>Why this never touches a {@code Location}</h2>
 * Containment is ordinary arithmetic against three numbers and a world name, and the source engine tested it
 * only by starting a server. Everything geometric here works on {@link Point}, which a listener builds from a
 * real {@code Location} at the door and never the other way around — the same seam
 * {@code BorderService.WorldBorderTarget} uses to keep the border's own arithmetic Bukkit-free. That listener
 * — the join handler that teleports a tribute in, and the PVP handler that cancels a hit — is somebody else's
 * file; this class only answers the two questions both of them need answered.
 *
 * <h2>A found bug: containment must ask the arena has been placed, not merely that a round exists</h2>
 * The source's {@code isActive()} treated "the lobby centre is set" as the one precondition and then asked
 * the phase separately in {@code isInside}'s caller — which meant a server that had run {@code /init} once,
 * long ago, and then reset to {@code NOT_INITIALIZED} without clearing the saved centre would still report
 * the box active during a phase nobody is in yet. Here {@link #isActive()} is the single source of truth for
 * both the geometry <em>and</em> the phase, so nothing downstream has to remember to ask both.
 */
public final class LobbyBoxService implements IHungerGamesService {

    /** A position, spelled out just enough to test containment — no Bukkit {@code World} needed. */
    public record Point(String worldName, double x, double y, double z) {
    }

    /** The lobby box's geometry for the round currently being prepared. */
    public record Box(Point arenaCentre, Point lobbyCentre) {
    }

    /** Where the arena and lobby are, once {@code /init} has placed them — empty before that. */
    @FunctionalInterface
    public interface ArenaGeometry {
        Optional<Box> current();
    }

    private final GameSession session;
    private final ArenaGeometry geometry;

    private HungerGamesSettings settings = HungerGamesSettings.DEFAULTS;

    public LobbyBoxService(GameSession session, ArenaGeometry geometry) {
        this.session = session;
        this.geometry = geometry;
    }

    @Override
    public void settings(HungerGamesSettings settings) {
        this.settings = settings;
    }

    /** Whether the glass box currently exists at all — from {@code /init} until the start-up sequence removes
     * it moving into {@link GamePhase#READY}. */
    public boolean isActive() {
        GamePhase phase = session.phase();
        return geometry.current().isPresent()
                && (phase == GamePhase.PREFLIGHT || phase == GamePhase.LOBBY || phase == GamePhase.STARTUP);
    }

    /** Whether {@code location} falls inside the box's walls, floor and roof — regardless of {@link #isActive()}. */
    public boolean isInside(Point location) {
        Optional<Box> box = geometry.current();
        if (box.isEmpty() || location == null
                || !box.get().arenaCentre().worldName().equals(location.worldName())) {
            return false;
        }
        Point centre = box.get().arenaCentre();
        int width = settings.lobbyWidth();
        int depth = settings.lobbyDepth();
        int height = settings.lobbyHeight();
        double baseX = centre.x() - width / 2.0;
        double baseY = centre.y() + settings.lobbyHeightOffset();
        double baseZ = centre.z() - depth / 2.0;
        return location.x() >= baseX && location.x() < baseX + width
                && location.z() >= baseZ && location.z() < baseZ + depth
                && location.y() >= baseY && location.y() <= baseY + height + 1;
    }

    /**
     * Whether a tribute arriving at {@code currentLocation} should be teleported into the box.
     *
     * <p>Not during {@link GamePhase#STARTUP}: by then tributes have already been taken underground for the
     * launch sequence, and pulling one back up into the lobby mid-sequence would undo exactly what that stage
     * is doing.
     */
    public boolean shouldRelocateOnJoin(UUID uuid, Point currentLocation) {
        return isActive() && session.phase() != GamePhase.STARTUP
                && session.isWhitelisted(uuid) && !isInside(currentLocation);
    }

    /** Where a relocated tribute should land — empty when there is nothing to relocate them into yet. */
    public Optional<Point> lobbyCentre() {
        return geometry.current().map(Box::lobbyCentre);
    }

    /** Whether a hit between these two locations must be cancelled: the box is up and either party is in it. */
    public boolean forbidsCombatBetween(Point attacker, Point victim) {
        return isActive() && (isInside(attacker) || isInside(victim));
    }
}
