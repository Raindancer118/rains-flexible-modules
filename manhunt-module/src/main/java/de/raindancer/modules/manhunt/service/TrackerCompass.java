package de.raindancer.modules.manhunt.service;

import de.raindancer.modules.manhunt.ManhuntSettings;
import de.raindancer.modules.manhunt.ManhuntSettings.CrossWorldTracking;
import de.raindancer.modules.manhunt.ManhuntSettings.TrackerTargets;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Which Runner a Hunter's compass points at, where the needle should aim, and how far away that is.
 * Bukkit-free on purpose, exactly like {@link ManhuntLobbyBox} and for the same reason: the decision
 * is arithmetic over a few numbers and a world name, so it is tested without a server (see
 * {@code TrackerCompassTest}) and {@code TrackerCompassService} converts a real {@code Location} into
 * a {@link Point} only at the door.
 *
 * <h2>Nearest, or the one the Hunter picked — the owner's call, not this class'</h2>
 * With one Runner there is nothing to decide. With several, a compass that silently re-aims at
 * whoever happens to be closest can be unusable — a Hunter chasing one Runner would be swung around
 * every time a second Runner crossed nearer — and yet on a server where the Runners stay together it
 * is exactly what is wanted. So both exist, under {@link TrackerTargets}: {@code NEAREST} ignores any
 * pick outright, {@code CHOSEN} keeps the Hunter's pick for as long as that Runner is still in the
 * roster this class is handed and only falls back to the nearest when they drop out of it.
 *
 * <h2>Another dimension is a third answer, not an absent one</h2>
 * A compass needle is a direction in one world; a Runner two worlds away has no direction to give —
 * but the door they went through does, and it is in the Hunter's own world. That is what
 * {@link CrossWorldTracking#LAST_PORTAL} points at, off {@link PortalMemory}: the Hunters follow the
 * Runner down rather than guessing where the Overworld ends and the chase begins. Where no crossing
 * was ever seen (a Runner who was already below when the hunt started, a death and a respawn in
 * another world) it degrades to {@code NAME_WORLD} rather than to nothing, because "they are in the
 * Nether" is still worth more to a Hunter than a spinning needle. {@code HIDDEN} is the hardest hunt
 * and says neither.
 *
 * <h2>Never stateful about who is being tracked</h2>
 * The Hunter's current pick is passed <em>in</em> on every call rather than remembered here, the same
 * reasoning {@link ManhuntLobbyBox} documents for its own source: a cache of "who is tracking whom"
 * drifts the moment a Runner dies, disconnects or leaves the side, while a decision re-derived from
 * the roster it is handed cannot.
 */
public final class TrackerCompass {

    /** A position, spelled out just enough to aim at — no Bukkit {@code World} needed. */
    public record Point(String worldName, double x, double y, double z) {

        /** Distance to {@code other}, or absent when the two are not in the same world. */
        public OptionalDistance distanceTo(Point other) {
            if (other == null || !worldName.equals(other.worldName())) {
                return OptionalDistance.none();
            }
            double dx = other.x() - x;
            double dy = other.y() - y;
            double dz = other.z() - z;
            return OptionalDistance.of(Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
    }

    /** A distance that may not exist — two points in different worlds have none. */
    public record OptionalDistance(boolean present, double blocks) {

        static OptionalDistance none() {
            return new OptionalDistance(false, 0);
        }

        static OptionalDistance of(double blocks) {
            return new OptionalDistance(true, blocks);
        }
    }

    /** A Runner the compass could point at. */
    public record Candidate(UUID id, Point at) {
    }

    /**
     * What a compass should be showing right now.
     *
     * @param kind      which of the four answers this is
     * @param target    the Runner followed, null for {@link Kind#NONE}
     * @param at        where the needle points — the Runner for {@link Kind#TRACKING}, the door they
     *                  went through for {@link Kind#PORTAL}, null otherwise. Always in the asking
     *                  Hunter's own world when it is set at all.
     * @param worldName the world the Runner is in — the Hunter's own for {@link Kind#TRACKING},
     *                  another one for {@link Kind#PORTAL} and {@link Kind#OTHER_WORLD}
     * @param distance  blocks to {@link #at}, 0 when there is nothing to point at
     */
    public record Aim(Kind kind, UUID target, Point at, String worldName, double distance) {

        /**
         * NONE: nothing to show. TRACKING: the Runner themselves, in this world. PORTAL: the door
         * they left this world by, with {@link #worldName} naming where that leads. OTHER_WORLD: only
         * the name of the dimension they are in.
         */
        public enum Kind { NONE, TRACKING, PORTAL, OTHER_WORLD }

        public static Aim none() {
            return new Aim(Kind.NONE, null, null, null, 0);
        }

        public static Aim tracking(UUID target, Point at, double distance) {
            return new Aim(Kind.TRACKING, target, at, at.worldName(), distance);
        }

        public static Aim portal(UUID target, Point door, String theirWorld, double distance) {
            return new Aim(Kind.PORTAL, target, door, theirWorld, distance);
        }

        public static Aim otherWorld(UUID target, String worldName) {
            return new Aim(Kind.OTHER_WORLD, target, null, worldName, 0);
        }

        /** Whether the needle has a real spot to swing to, rather than only something to say. */
        public boolean hasDirection() {
            return kind == Kind.TRACKING || kind == Kind.PORTAL;
        }
    }

    private final PortalMemory portals;

    private volatile ManhuntSettings settings;

    public TrackerCompass(ManhuntSettings settings, PortalMemory portals) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.portals = Objects.requireNonNull(portals, "portals");
    }

    /** Told the live settings whenever they change — wired via {@code SettingsStore.onChange}. */
    public void settings(ManhuntSettings fresh) {
        this.settings = fresh;
    }

    /** Whether the Hunters carry a compass at all right now. */
    public boolean isEnabled() {
        return settings.trackerCompassEnabled();
    }

    /** Whether a Hunter may pick which Runner to follow, or the needle is always the owner's choice. */
    public boolean allowsPicking() {
        return settings.trackerTargets() == TrackerTargets.CHOSEN;
    }

    /** Whether the block distance belongs in the item's lore. */
    public boolean showsDistance() {
        return settings.trackerShowDistance();
    }

    /**
     * Where {@code hunter}'s compass should point.
     *
     * @param hunter    where the Hunter is standing
     * @param runners   every Runner still worth pointing at — living, online, on the Runner side
     * @param picked    the Runner this Hunter chose, or null; ignored under {@link TrackerTargets#NEAREST}
     */
    public Aim aim(Point hunter, List<Candidate> runners, UUID picked) {
        ManhuntSettings config = settings;
        if (!config.trackerCompassEnabled() || hunter == null || runners == null || runners.isEmpty()) {
            return Aim.none();
        }
        Candidate chosen = chooseTarget(config, hunter, runners, picked);
        OptionalDistance direct = hunter.distanceTo(chosen.at());
        if (direct.present()) {
            return Aim.tracking(chosen.id(), chosen.at(), direct.blocks());
        }
        return acrossDimensions(config, hunter, chosen);
    }

    private Candidate chooseTarget(ManhuntSettings config, Point hunter, List<Candidate> runners,
                                   UUID picked) {
        if (config.trackerTargets() == TrackerTargets.CHOSEN) {
            Optional<Candidate> stuck = find(runners, picked);
            if (stuck.isPresent()) {
                return stuck.get();
            }
        }
        return nearest(config, hunter, runners);
    }

    /**
     * The nearest Runner in the Hunter's own world. Where none of them share it, the one whose known
     * door out of it is nearest — a Hunter left alone in the Overworld is still owed the closest way
     * down, not whoever happens to sit first in the roster. Failing even that, the first Runner, so a
     * compass always names somebody rather than going quiet on a hunt that is very much still on.
     */
    private Candidate nearest(ManhuntSettings config, Point hunter, List<Candidate> runners) {
        Candidate best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Candidate candidate : runners) {
            OptionalDistance distance = hunter.distanceTo(candidate.at());
            if (distance.present() && distance.blocks() < bestDistance) {
                best = candidate;
                bestDistance = distance.blocks();
            }
        }
        if (best != null) {
            return best;
        }
        if (config.trackerCrossWorld() == CrossWorldTracking.LAST_PORTAL) {
            for (Candidate candidate : runners) {
                Optional<Point> door = portals.lastCrossingIn(candidate.id(), hunter.worldName());
                if (door.isEmpty()) {
                    continue;
                }
                OptionalDistance distance = hunter.distanceTo(door.get());
                if (distance.present() && distance.blocks() < bestDistance) {
                    best = candidate;
                    bestDistance = distance.blocks();
                }
            }
        }
        return best != null ? best : runners.get(0);
    }

    private Aim acrossDimensions(ManhuntSettings config, Point hunter, Candidate chosen) {
        String theirWorld = chosen.at().worldName();
        return switch (config.trackerCrossWorld()) {
            case HIDDEN -> Aim.none();
            case NAME_WORLD -> Aim.otherWorld(chosen.id(), theirWorld);
            case LAST_PORTAL -> portals.lastCrossingIn(chosen.id(), hunter.worldName())
                    .map(door -> Aim.portal(chosen.id(), door, theirWorld,
                            hunter.distanceTo(door).blocks()))
                    // No door on record — a Runner already below when the hunt started, or one who
                    // died and respawned somewhere else. Naming the dimension still beats a spin.
                    .orElseGet(() -> Aim.otherWorld(chosen.id(), theirWorld));
        };
    }

    private static Optional<Candidate> find(List<Candidate> runners, UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return runners.stream().filter(candidate -> id.equals(candidate.id())).findFirst();
    }

    /**
     * The Runner after {@code current} in the roster, wrapping at the end — what a right-click on the
     * compass moves to. Empty only when there is nobody left to point at at all; a lone Runner cycles
     * to themselves, since "the only Runner" and "no Runner" are different answers and a Hunter
     * clicking should not be able to switch their own compass off.
     */
    public static Optional<UUID> next(List<Candidate> runners, UUID current) {
        if (runners == null || runners.isEmpty()) {
            return Optional.empty();
        }
        int index = -1;
        for (int i = 0; i < runners.size(); i++) {
            if (runners.get(i).id().equals(current)) {
                index = i;
                break;
            }
        }
        return Optional.of(runners.get((index + 1) % runners.size()).id());
    }
}
