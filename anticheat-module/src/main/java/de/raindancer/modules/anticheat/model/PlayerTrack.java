package de.raindancer.modules.anticheat.model;

import org.bukkit.Location;
import org.bukkit.util.BoundingBox;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.LongSupplier;

/**
 * Everything the checks remember about one player while they are online.
 *
 * <p>Written from two threads: the Netty thread (packets) and the player's own region thread (events
 * and the per-tick engine). The engine holds this object's monitor for its whole tick, so the Netty
 * thread never takes it — packet state is guarded by {@link #wire}, clicks by {@code combat}'s own
 * monitor, and what both sides share is in concurrent collections.
 */
public final class PlayerTrack {

    public static final int POSITION_HISTORY = 30;

    private final UUID id;
    private final String name;
    private final LongSupplier clock;
    private final Violations violations;
    private final Map<CheckType, Buffer> buffers = new EnumMap<>(CheckType.class);
    private final Map<Exemption, Long> exemptUntil = new EnumMap<>(Exemption.class);

    /** Failures found on a packet thread, raised on the player's own thread at the next tick. */
    public final ConcurrentLinkedQueue<Flag> pendingFlags = new ConcurrentLinkedQueue<>();

    /** Guards {@link #packets} and the per-tick target set: the Netty thread's lock, never held long. */
    public final Object wire = new Object();

    /** Samples handed over by the packet tap or the move listener, drained once a tick. */
    public final ConcurrentLinkedQueue<MoveSample> samples = new ConcurrentLinkedQueue<>();

    public final Movement movement = new Movement();
    public final Combat combat = new Combat();
    public final World world = new World();
    public final Packets packets = new Packets();
    public final TimerBalance timer = new TimerBalance(1000);
    /** The last five seconds of movement, frozen into a replay whenever a check alerts. */
    public final ReplayRecorder recorder = new ReplayRecorder(100);
    /** Ticks without a move, for Blink. */
    public final BlinkWatch blink = new BlinkWatch();
    /** On the respawn screen: a dead client sends no moves, and is no blink. */
    public volatile boolean dead;

    /** Refreshed once a second on the player's own thread, so packet threads never ask permissions. */
    public volatile boolean bypassAll;
    public final Set<CheckType> bypassed = java.util.concurrent.ConcurrentHashMap.newKeySet();
    public volatile boolean bedrock;
    /** The client's own game version when ViaVersion translates it, else null. */
    public volatile String translatedFrom;
    private volatile boolean versionKnown;

    public boolean versionKnown() {
        return versionKnown;
    }

    public void version(String translatedFrom) {
        this.translatedFrom = translatedFrom;
        this.versionKnown = true;
    }
    public volatile boolean kicking;
    public volatile int ping;
    public volatile int entityId = Integer.MIN_VALUE;

    /**
     * The ping lag compensation may assume, capped: a client can fake any ping by holding back its
     * keep-alive answers, so ping only ever widens tolerances up to the cap and never switches a check off.
     */
    public int compensated(int maxPing) {
        return Math.max(0, Math.min(ping, maxPing));
    }

    /** Why movement checks are off for a while. */
    public enum Exemption {
        JOINED, TELEPORTED, RESPAWNED, WORLD_CHANGED, GAME_MODE, VEHICLE, FLIGHT, ELYTRA, RIPTIDE,
        PISTON, EFFECT, MANUAL, BLOCK_UNDERFOOT, BOUNCE,
        /** A firework under an elytra: each one is used up, so unlike the glide toggle it needs no budget. */
        ELYTRA_BOOST
    }

    public PlayerTrack(UUID id, String name, LongSupplier clock) {
        this.id = id;
        this.name = name;
        this.clock = clock;
        this.violations = new Violations(clock);
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Violations violations() {
        return violations;
    }

    public long now() {
        return clock.getAsLong();
    }

    /** The evidence buffer of one check, made the first time it is asked for. */
    /**
     * Everything the checks have measured on this player, started over — as if they had just joined for the
     * first time. What they are excused from right now and where they stand are left as they are: those are
     * facts about the moment, not about the player.
     */
    public synchronized void measureAfresh() {
        violations.resetAll();
        buffers.clear();
        synchronized (combat) {
            combat.clickIntervals.clear();
            combat.clicks.clear();
            combat.pitchDeltas.clear();
            combat.fishReactions.clear();
        }
        movement.rises.clear();
        recorder.clear();
    }

    public synchronized Buffer buffer(CheckType check, double limit, double forgiveness) {
        return buffers.computeIfAbsent(check, ignored -> new Buffer(limit, forgiveness));
    }

    /** How long a window the budget of a client-triggered exemption is counted over, and how much of it may be excused. */
    static final long BUDGET_WINDOW = 10_000;
    static final long BUDGET = 4_000;
    /** {granted at, millis newly covered} in the current window — one budget for every kind, so taking turns gains nothing. */
    private final java.util.ArrayDeque<long[]> granted = new java.util.ArrayDeque<>();

    /**
     * Exemptions the client can set off as often as it likes. Each is real once; repeated, they would
     * cover a cheater for good, so only so much of each is granted per window.
     */
    private static boolean budgeted(Exemption why) {
        return switch (why) {
            case ELYTRA, VEHICLE, PISTON, RIPTIDE, BOUNCE -> true;
            default -> false;
        };
    }

    public synchronized void exempt(Exemption why, long millis) {
        long now = now();
        long until = now + Math.max(0, millis);
        if (budgeted(why)) {
            long already = Math.max(now, exemptUntil.getOrDefault(why, 0L));
            long fresh = Math.max(0, until - already);
            // New cover counts only beyond what any budgeted kind already gives.
            long coveredByAny = already;
            for (Exemption other : Exemption.values()) {
                if (budgeted(other)) {
                    coveredByAny = Math.max(coveredByAny, exemptUntil.getOrDefault(other, 0L));
                }
            }
            fresh = Math.max(0, until - coveredByAny);
            java.util.ArrayDeque<long[]> spent = granted;
            while (!spent.isEmpty() && now - spent.peekFirst()[0] > BUDGET_WINDOW) {
                spent.removeFirst();
            }
            long used = spent.stream().mapToLong(grant -> grant[1]).sum();
            fresh = Math.min(fresh, Math.max(0, BUDGET - used));
            if (fresh <= 0) {
                return;
            }
            spent.addLast(new long[]{now, fresh});
            until = already + fresh;
        }
        exemptUntil.merge(why, until, Math::max);
    }

    public synchronized void unexempt(Exemption why) {
        exemptUntil.remove(why);
    }

    /**
     * The first reason that still holds, or null. A firework boost is not among them: it excuses the elytra
     * check alone, and fireworks are cheap enough to keep one burning.
     */
    public synchronized Exemption exemption() {
        long now = now();
        for (Map.Entry<Exemption, Long> entry : exemptUntil.entrySet()) {
            if (entry.getKey() != Exemption.ELYTRA_BOOST && entry.getValue() > now) {
                return entry.getKey();
            }
        }
        return null;
    }

    public synchronized boolean isExempt(Exemption why) {
        Long until = exemptUntil.get(why);
        return until != null && until > now();
    }

    public synchronized long exemptMillisLeft(Exemption why) {
        Long until = exemptUntil.get(why);
        return until == null ? 0 : Math.max(0, until - now());
    }

    /** Where they are, as the checks last accepted it, and what they were doing. */
    public static final class Movement {
        public boolean known;
        public String worldName;
        public double x;
        public double y;
        public double z;
        public float yaw;
        public float pitch;
        public double lastDy = Double.NaN;
        public double lastHd = Double.NaN;
        /** The last moves as the next tick's limits build on them: no more than was allowed. NaN when unknown. */
        public double carryDy = Double.NaN;
        public double carryHd = Double.NaN;
        /** This tick's limits, once judged; NaN or null when the check did not run. */
        public double hdLimit = Double.NaN;
        public de.raindancer.modules.anticheat.rules.VerticalRule.Bounds dyBounds;
        public double lastDx = Double.NaN;
        public double lastDz = Double.NaN;
        public boolean lastCollided;
        public boolean onGround;
        public boolean onGroundBefore;
        public double friction = 0.6;
        public double frictionBefore = 0.6;
        public boolean inside;
        public boolean special;
        public boolean bouncy;
        public double fallSpeed;
        public double jumpFactor = 1;
        public float ourFallDistance;
        public long ticks;
        public int airTicks;
        public double highestY = Double.NaN;
        public final Samples rises = new Samples(12);
        public float fallDistance;
        /** Where a setback sends them: the last place every check was happy with, on the ground. */
        public Location legit;
        public long lastSetbackMillis;
        /** A teleport the client has not answered yet; samples before it arrives are stale. */
        public Location teleportTarget;
        public long teleportAtMillis;
        public long lastSampleNanos;
        public boolean jesusSurface;
        public int jesusTicks;
        public int glideClimbTicks;
        public int vehicleClimbTicks;
        public final Excuses excuses = new Excuses();
        /**
         * Pending knockback: vx, vy, vz, sentAtMillis, highestRiseSince, blocked (1 when a ceiling, liquid
         * or web could explain it), transaction id (0 none), millis the client confirmed it (0 not yet).
         */
        public final Deque<double[]> velocities = new java.util.concurrent.ConcurrentLinkedDeque<>();
        public boolean sprintHitThisTick;
        public long sprintHitMillis;
        /** The last input the client reported. */
        public boolean forward;
        public boolean backward;
        public boolean left;
        public boolean right;
        public boolean jump;
        public boolean sneak;
        public boolean sprint;
        public long inputChangedMillis;
        public boolean inputSeen;
        /** The sneak key and item use, move by move; never reset by a teleport, their budget is the client's own doing. */
        public final SlowState sneakState = new SlowState();
        public final SlowState usingState = new SlowState();
        /** Consecutive judged moves made with no direction key down, as the client itself reported them. */
        public int idleMoves;

        public void forget() {
            known = false;
            lastDy = Double.NaN;
            lastHd = Double.NaN;
            carryDy = Double.NaN;
            carryHd = Double.NaN;
            hdLimit = Double.NaN;
            dyBounds = null;
            lastDx = Double.NaN;
            lastDz = Double.NaN;
            airTicks = 0;
            highestY = Double.NaN;
            rises.clear();
            jesusTicks = 0;
            glideClimbTicks = 0;
            vehicleClimbTicks = 0;
            idleMoves = 0;
        }

        public boolean moving() {
            return forward || backward || left || right;
        }
    }

    /** Attacks, clicks and where they themselves stood, for when they are the target. */
    public static final class Combat {
        /** Where this player has been, newest last: {millis, x, y, z, width, height}. */
        public final Deque<double[]> history = new ArrayDeque<>();
        /** Look directions the client sent recently, newest last: {yaw, pitch}. */
        public final Deque<float[]> rotations = new ArrayDeque<>();
        public final Samples pitchDeltas = new Samples(40);
        public double lastRawYawDelta;
        public float lastRawYaw = Float.NaN;
        public final Samples clickIntervals = new Samples(de.raindancer.modules.anticheat.rules.ClickRule.PATTERN_SAMPLE);
        public final Deque<Long> clicks = new ArrayDeque<>();
        public long lastClickMillis;
        public long lastUseMillis;
        public long lastAttackMillis;
        /** Distinct targets hit in the current client tick. */
        public final Set<Integer> targetsThisTick = new HashSet<>();
        public long targetsTickStamp;
        /** Attacks still waiting for their swing: millis of each. */
        public final Deque<Long> unswung = new java.util.concurrent.ConcurrentLinkedDeque<>();
        /** Hits waiting for the next look direction before their hitbox is judged. */
        public final Deque<PendingHit> pendingHits = new ArrayDeque<>();
        public long totemPoppedMillis;
        public long lastInventoryClickMillis;
        public long fishBiteMillis;
        public final Samples fishReactions = new Samples(10);
    }

    /** A hit whose crosshair question waits for the next rotation. */
    public record PendingHit(long atMillis, String targetName, java.util.List<org.bukkit.util.Vector> eyes,
                             java.util.List<BoundingBox> boxes, double reach, double margin) {
    }

    /** Digging, placing and containers. */
    public static final class World {
        public int digX = Integer.MIN_VALUE;
        public int digY;
        public int digZ;
        public long digStartMillis;
        public double digSpeedAtStart;
        public long lastBreakMillis;
        public double fastBreakBalance;
        public final Deque<Long> starts = new ArrayDeque<>();
        public final Deque<Long> places = new ArrayDeque<>();
        public long containerOpenedMillis;
        public final Deque<Long> containerClicks = new ArrayDeque<>();
        public long cancelledPlaceMillis;

        public boolean digging() {
            return digX != Integer.MIN_VALUE;
        }

        public void stopDigging() {
            digX = Integer.MIN_VALUE;
        }
    }

    /** What arrived on the wire, in order. */
    public static final class Packets {
        public volatile boolean tapped;
        public volatile boolean sendsTickEnd;
        public int movesThisTick;
        public boolean movedThisTick;
        /** The keys the client last reported on the wire, {@link Inputs}; stamped onto every move after it. */
        public int input = Inputs.UNKNOWN;
        public float lastYaw = Float.NaN;
        public float lastPitch = Float.NaN;
        public int lastSlot = -1;
        public boolean sprintingByCommand;
        public int ticksSinceTickEnd;
        public boolean usedThisTick;
        public boolean digging;
        public int attacksAwaitingSwing;
        public boolean swung;
        public long lastSwingNanos;
        /** In or on a vehicle or mount, as of the last tick — set by the player's own thread. */
        public volatile boolean riding;
        public int startsThisTick;
        /** Our own ping packets in flight: id → nanos sent. */
        public final java.util.LinkedHashMap<Integer, Long> transactions = new java.util.LinkedHashMap<>();
        /** Block changes sent near the player and not yet answered. */
        public final PendingBlocks blocks = new PendingBlocks();
        public final Samples roundTrips = new Samples(16);
        public int nextTransaction;
    }
}
