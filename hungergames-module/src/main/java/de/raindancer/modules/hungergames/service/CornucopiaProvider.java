package de.raindancer.modules.hungergames.service;

import de.raindancer.core.world.protection.LandAction;
import de.raindancer.core.world.protection.LandAudience;
import de.raindancer.core.world.protection.LandFlag;
import de.raindancer.core.world.protection.LandProvider;
import de.raindancer.core.world.protection.ProtectedArea;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.rules.ProtectionRules;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The cornucopia, told to RainsCore as a protected area.
 *
 * <h2>Why this is a provider and not a listener</h2>
 * The first version of this was a listener: three {@code @EventHandler} methods that asked
 * {@link ProtectionRules} and cancelled the event. It worked, and it was the wrong shape, because Core
 * already owns every part of that job — {@code BlockProtectionListener},
 * {@code InteractionProtectionListener}, {@code MovementProtectionListener} and {@code MobControlListener}
 * are already registered on any server running RainsCore, and {@code Land} is already the arbiter they
 * ask.
 *
 * <p>What the module actually knows, and the only thing it knows, is <b>where the cornucopia is and
 * whether it is currently closed</b>. So that is all this says. Everything else follows from Core:
 *
 * <ul>
 *   <li><b>Far more events are covered than a hand-written listener would have reached.</b> The version
 *       this replaces handled break, place and right-click. Core's listeners also cover buckets, signs,
 *       multi-block placement, harvesting, ignition, item frames, vehicles, farmland and pistons. Every
 *       one of those is a way to take a cornucopia apart, and every one of them was a hole.</li>
 *   <li><b>The bypass is Core's, including the part that is easy to forget.</b> {@code Land} tracks who is
 *       bypassing and asks them, in chat, every ten minutes whether they still mean to be — which is the
 *       fix for a real incident on this server, where a bypass left on silently overrode a claim's own
 *       settings for days. A module with its own permission check gets none of that.</li>
 *   <li><b>The refusal message is Core's,</b> so being refused by the cornucopia reads exactly like being
 *       refused by a claim. A module-specific sentence would have been one more thing for a player to have
 *       to learn, and it needed a cooldown of its own that Core's already has.</li>
 * </ul>
 *
 * <p>What is left here is about twenty lines of geometry and one call into the matrix. That is the correct
 * size for this module's share of the problem.
 *
 * <h2>The one thing that is genuinely different from a claim</h2>
 * A claim is protected because somebody owns it. The cornucopia is protected <em>because of the time of
 * day</em> — closed while forty people stand on their platforms, open the moment the bell rings, closed
 * again after the round if the server wants the next one to start in a whole arena. So {@link #at} returns
 * empty rather than an area whenever the phase does not protect it: a location that is not in a protected
 * area at all is cheaper for Core to answer about than one whose every flag is permissive, and it means
 * nothing greys a button or shows a boundary during the round.
 */
public final class CornucopiaProvider implements LandProvider, IHungerGamesService {

    /** What Core calls this area. One area, so one id. */
    public static final String AREA_ID = "hungergames:cornucopia";

    private final Supplier<GamePhase> phase;
    private final Supplier<Location> arenaCentre;

    /**
     * Rebuilt on a settings swap rather than per query.
     *
     * <p>{@code at} is called by Core on every block break, every place and every right-click on the
     * server, several times a tick, from whichever region thread owns the block. Volatile because the swap
     * arrives on the server thread and the reads do not.
     */
    private volatile ProtectionRules rules;
    private volatile int radius;

    public CornucopiaProvider(HungerGamesSettings settings, Supplier<GamePhase> phase,
                              Supplier<Location> arenaCentre) {
        this.phase = phase;
        this.arenaCentre = arenaCentre;
        settings(settings);
    }

    @Override
    public void settings(HungerGamesSettings settings) {
        // protection.allowed-interactions is deliberately absent: it moved to Core's LandFlags in the
        // port. "Which blocks may be used despite protection" is the same question every protected area on
        // the server asks, and two answers to it is how a chest opens inside a claim and not inside the
        // cornucopia. The matrix keeps its own default set for the CONTAINER decision below.
        this.rules = new ProtectionRules(new ProtectionRules.Config(
                settings.protectCornucopiaBeforeRunning(),
                settings.protectCornucopiaDuringRunning(),
                settings.protectCornucopiaAfterGame(),
                ProtectionRules.Config.defaults().allowedMaterials()));
        this.radius = settings.cornucopiaRadius();
    }

    @Override
    public String name() {
        return "The Hunger Games cornucopia";
    }

    /**
     * The cornucopia, when this location is inside it and the phase currently closes it.
     *
     * <p>Never throws. Core asks this on a hot path to decide whether to cancel an event, and an exception
     * unwinding through it would leave the event uncancelled — which is protection switching itself off,
     * server-wide, for every plugin that goes through {@code Land}. That is a considerably worse failure
     * than the one a module-local listener could cause, which is exactly why the guard is here.
     */
    @Override
    public Optional<ProtectedArea> at(Location location) {
        try {
            if (!isInsideTheCornucopia(location)) {
                return Optional.empty();
            }
            GamePhase now = phase.get();
            if (!closedDuring(now)) {
                // Open. Empty rather than a permissive area: Core answers faster about a location in no
                // area at all, and nothing draws a boundary around the middle mid-round.
                return Optional.empty();
            }
            return Optional.of(new Cornucopia(now));
        } catch (RuntimeException unexpected) {
            // Allowing rather than refusing, deliberately. A bug that refused everything would stop every
            // player on the server building anywhere; one that allows something is a hole in the
            // cornucopia. Neither is acceptable and this is the recoverable one.
            return Optional.empty();
        }
    }

    /**
     * Whether this world could contain the cornucopia at all.
     *
     * <p>Core asks before doing anything world-wide. Answering true for every world would make it consult
     * this provider about the survival world, the nether and every farm world on the server.
     */
    @Override
    public boolean hasAnyIn(World world) {
        Location centre = arenaCentre.get();
        return world != null && centre != null && world.equals(centre.getWorld());
    }

    // ==================== geometry ====================

    /**
     * Whether a location is within the cornucopia's radius, in the arena's own world.
     *
     * <p>Public because it is a question other things genuinely ask: a command reporting where somebody is
     * standing, a screen greying a button, the start-up sequence checking it is not about to paste over the
     * middle. Answering it in each of those places would be the same circle computed four ways.
     *
     * <p>The world check is not a formality. The cornucopia is a circle around one point in one world, and
     * without it a matching circle would be protected at the same coordinates in every world on the
     * server — including spawn, which is usually near the origin too.
     */
    public boolean isInsideTheCornucopia(Location location) {
        Location centre = arenaCentre.get();
        if (location == null || centre == null || centre.getWorld() == null
                || !centre.getWorld().equals(location.getWorld())) {
            return false;
        }
        double dx = location.getX() - centre.getX();
        double dz = location.getZ() - centre.getZ();
        double reach = radius;
        // Squared, so nothing takes a square root on a path that runs several times a tick. <= so the ring
        // of blocks making up the cornucopia's own wall is inside it — that wall is the thing most likely
        // to be mined by somebody standing just outside.
        return (dx * dx + dz * dz) <= reach * reach;
    }

    /** Whether the matrix closes the cornucopia in this phase at all. */
    public boolean closedDuring(GamePhase now) {
        // Asked as "would breaking a stone block here be refused", which is what the matrix answers. A
        // phase where breaking is allowed is a phase where there is no area to report.
        return rules.shouldDeny(new ProtectionRules.Query(
                ProtectionRules.Region.CORNUCOPIA, ProtectionRules.ActionType.BREAK, now, false, "STONE"));
    }

    // ==================== the area itself ====================

    /**
     * The cornucopia as Core sees it: an area nobody owns, that everybody is a visitor in, and that refuses
     * building rather than entry.
     *
     * <p>Built fresh per query rather than cached, because it carries the phase it was built for — an area
     * object that outlived the phase would answer about a round that has moved on. It is a record of two
     * fields; building one costs less than the map lookup caching it would need.
     */
    private final class Cornucopia implements ProtectedArea {

        private final GamePhase asOf;

        private Cornucopia(GamePhase asOf) {
            this.asOf = asOf;
        }

        @Override
        public String id() {
            return AREA_ID;
        }

        @Override
        public String name() {
            return "the Cornucopia";
        }

        /**
         * Nobody. The cornucopia belongs to the tournament rather than to a player.
         *
         * <p>Which means every tribute is a {@link LandAudience#VISITOR} in it, and that is the right
         * answer: the whole point is that it treats all forty of them identically. An owner list with the
         * admin who ran {@code /init} in it would quietly exempt one player from the thing everybody else
         * is held to.
         */
        @Override
        public List<UUID> owners() {
            return List.of();
        }

        /**
         * No flag overrides. PvP, mob spawning and the rest are the server's business inside the arena as
         * much as outside it — a cornucopia that switched PvP off would be a safe square in the middle of a
         * fight to the death.
         */
        @Override
        public Optional<Boolean> flagOverride(LandFlag flag, LandAudience audience) {
            return Optional.empty();
        }

        @Override
        public LandAudience audienceOf(UUID player) {
            return LandAudience.VISITOR;
        }

        /**
         * What a tribute may do here.
         *
         * <p>Entering is always allowed — the cornucopia is where the round starts and a barrier around it
         * would be a different game. Containers are always allowed, because a round where tributes cannot
         * open the cornucopia chests is not a Hunger Games round. Everything that changes the structure is
         * put to the matrix.
         */
        @Override
        public boolean may(UUID player, LandAction action) {
            return switch (action) {
                case ENTER -> true;
                case CONTAINERS -> true;
                default -> !rules.shouldDeny(new ProtectionRules.Query(
                        ProtectionRules.Region.CORNUCOPIA, translate(action), asOf, false, ""));
            };
        }
    }

    /**
     * A Core action as one of the matrix's four.
     *
     * <p>Core has seventeen actions and the matrix has four, so this is a narrowing and the default matters:
     * anything not obviously reading or entering is treated as {@link ProtectionRules.ActionType#BREAK},
     * the strictest of the four. An action added to Core tomorrow therefore arrives protected rather than
     * arriving as a hole — which is the direction a default has to fall in a class like this.
     */
    public static ProtectionRules.ActionType translate(LandAction action) {
        return switch (action) {
            case BUILD, FARMLAND, BUCKETS -> ProtectionRules.ActionType.PLACE;
            case CONTAINERS -> ProtectionRules.ActionType.CONTAINER;
            case DOORS, REDSTONE, BEDS, WORKSTATIONS, TRADE, ITEM_PICKUP ->
                    ProtectionRules.ActionType.INTERACT;
            default -> ProtectionRules.ActionType.BREAK;
        };
    }

    @Override
    public String describe() {
        return "telling Core where the cornucopia is and when it is closed";
    }
}
