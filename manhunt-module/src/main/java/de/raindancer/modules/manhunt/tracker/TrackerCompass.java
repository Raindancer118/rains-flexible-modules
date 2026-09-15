package de.raindancer.modules.manhunt.tracker;

import de.raindancer.modules.manhunt.ManhuntSettings;
import de.raindancer.modules.manhunt.ManhuntSettings.CrossWorldTracking;

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
 * <h2>Nearest, or the one the Hunter picked — the Hunter's call, unless the owner took it away</h2>
 * With one Runner there is nothing to decide. With several, a compass that silently re-aims at
 * whoever happens to be closest can be unusable — a Hunter chasing one Runner would be swung around
 * every time a second Runner crossed nearer — and yet on a server where the Runners stay together it
 * is exactly what is wanted. Which of the two a Hunter wants is not something an owner can know in
 * advance, so by default each Hunter decides for their own compass, by right-clicking it: the roster
 * and "whoever is nearest" are the positions of one cycle (see {@link #next}), and a pick is kept for
 * as long as that Runner is still in the roster this class is handed.
 *
 * <p>{@link ManhuntSettings#trackerHunterMayChoose()} is the owner taking that back. Off, a pick is
 * not merely un-settable but ignored outright — an owner who switches it off mid-hunt means it from
 * that moment, not from the next hunt, and every needle goes back to the nearest Runner.
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

    /** Whether a Hunter may aim their own compass, or the needle is fixed to what the owner set. */
    public boolean allowsPicking() {
        return settings.trackerHunterMayChoose();
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
     * @param picked    what this Hunter set their own compass to, or null for a Hunter who has never
     *                  touched it — ignored entirely when
     *                  {@link ManhuntSettings#trackerHunterMayChoose()} is off
     */
    public Aim aim(Point hunter, List<Candidate> runners, Following picked) {
        ManhuntSettings config = settings;
        if (hunter == null || runners == null || runners.isEmpty()) {
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
                                   Following picked) {
        if (config.trackerHunterMayChoose() && picked != null) {
            if (picked.isNearest()) {
                return nearest(config, hunter, runners);
            }
            Optional<Candidate> stuck = find(runners, picked.runner());
            if (stuck.isPresent()) {
                return stuck.get();
            }
            // The Runner they were on is out of the hunt. The nearest is the only honest answer
            // left, and it is also where next() will start counting from again.
            return nearest(config, hunter, runners);
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

    /** One position of a Hunter's compass: a named Runner, or the nearest, whoever that turns out to be. */
    public record Following(UUID runner) {

        /** No particular Runner — the needle goes back to swinging at whoever is closest. */
        public static final Following NEAREST = new Following(null);

        public static Following of(UUID runner) {
            return new Following(Objects.requireNonNull(runner, "runner"));
        }

        public boolean isNearest() {
            return runner == null;
        }
    }

    /**
     * Where a right-click on the compass moves it: the Runner after {@code current} in the roster,
     * and {@link Following#NEAREST} after the last of them — so the cycle is
     * nearest → first → … → last → nearest and a Hunter can always get back to where they started
     * without walking the whole roster twice.
     *
     * <p>{@code null} for {@code current}, or {@link Following#NEAREST}, both mean the compass is on
     * the nearest right now. Empty only when there is nobody left to point at at all; note that a
     * single Runner still gives two positions rather than one, because "locked on Anna" and "whoever
     * is nearest, who happens to be Anna" stop being the same answer the moment a second Runner
     * joins — and a click that visibly does nothing reads as broken.
     */
    public static Optional<Following> next(List<Candidate> runners, Following current) {
        if (runners == null || runners.isEmpty()) {
            return Optional.empty();
        }
        if (current == null || current.isNearest()) {
            return Optional.of(Following.of(runners.get(0).id()));
        }
        for (int i = 0; i < runners.size(); i++) {
            if (runners.get(i).id().equals(current.runner())) {
                return Optional.of(i + 1 < runners.size()
                        ? Following.of(runners.get(i + 1).id())
                        : Following.NEAREST);
            }
        }
        // The Runner they were on has left the hunt: start the cycle over rather than dropping them
        // to the nearest, which is where the compass has already fallen back to on its own.
        return Optional.of(Following.of(runners.get(0).id()));
    }
}
