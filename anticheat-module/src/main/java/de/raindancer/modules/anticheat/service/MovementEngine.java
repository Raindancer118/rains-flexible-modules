package de.raindancer.modules.anticheat.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.anticheat.AntiCheatSettings;
import de.raindancer.modules.anticheat.model.Buffer;
import de.raindancer.modules.anticheat.model.CheckType;
import de.raindancer.modules.anticheat.model.Flag;
import de.raindancer.modules.anticheat.model.MoveSample;
import de.raindancer.modules.anticheat.model.PlayerTrack;
import de.raindancer.modules.anticheat.rules.ActionRule;
import de.raindancer.modules.anticheat.rules.AimRule;
import de.raindancer.modules.anticheat.rules.CombatRule;
import de.raindancer.modules.anticheat.rules.HorizontalRule;
import de.raindancer.modules.anticheat.rules.Judgement;
import de.raindancer.modules.anticheat.rules.MotionRule;
import de.raindancer.modules.anticheat.rules.Physics;
import de.raindancer.modules.anticheat.rules.VerticalRule;
import de.raindancer.modules.anticheat.util.PermissionNodes;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.UseEffects;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Once a tick, on each player's own thread: takes the moves their client reported since the last
 * tick, reads the world around each one, and asks the movement rules about it. Also settles what
 * other checks left waiting for a tick to pass: hits waiting for their rotation, attacks waiting for
 * their swing, knockback waiting to be taken, and failures found on the packet thread.
 */
public final class MovementEngine implements IAntiCheatService {

    private static final long TELEPORT_PATIENCE_MILLIS = 4000;

    private final Plugin plugin;
    private final de.raindancer.core.platform.log.LogChannel log;
    private final Tracks tracks;
    private final ViolationService violations;
    private final VerticalRule vertical = new VerticalRule();
    private final HorizontalRule horizontal = new HorizontalRule();
    private final MotionRule motion = new MotionRule();
    private final CombatRule combat = new CombatRule();
    private final AimRule aim = new AimRule();
    private final de.raindancer.modules.anticheat.rules.AirControlRule airControl = new de.raindancer.modules.anticheat.rules.AirControlRule();
    private final de.raindancer.modules.anticheat.rules.PingRule pings = new de.raindancer.modules.anticheat.rules.PingRule();
    private volatile PacketTap tap;
    private volatile AntiCheatSettings settings = AntiCheatSettings.DEFAULTS;

    public MovementEngine(Plugin plugin, de.raindancer.core.platform.log.LogChannel log, Tracks tracks, ViolationService violations) {
        this.plugin = plugin;
        this.log = log;
        this.tracks = tracks;
        this.violations = violations;
    }

    /** The tap that measures each player's real round trip; without it, the server's keep-alive ping is used. */
    public void probesWith(PacketTap tap) {
        this.tap = tap;
    }

    /** Starts the tick for one player; re-arms itself if the server retires their entity. */
    public void start(Player player) {
        Scheduling.entityTimer(plugin, player, 1, 1, task -> {
            if (!player.isOnline()) {
                task.cancel();
                return;
            }
            try {
                tick(player);
            } catch (RuntimeException failed) {
                log.warn(failed, "The anti-cheat tick failed for {}.", player.getName());
            }
        }, () -> Scheduling.globalLater(plugin, 2, () -> {
            Player again = Bukkit.getPlayer(player.getUniqueId());
            if (again != null && again.isOnline()) {
                start(again);
            }
        }));
    }

    void tick(Player player) {
        PlayerTrack track = tracks.of(player);
        long now = track.now();
        PlayerTrack.Movement m = track.movement;
        synchronized (track) {
            m.ticks++;
            if (m.ticks % 20 == 1) {
                refreshPermissions(player, track);
                measurePing(player, track);
            }
            recordPosition(player, track, now);
        }
        List<MoveSample> drained = new ArrayList<>();
        for (MoveSample sample; (sample = track.samples.poll()) != null; ) {
            drained.add(sample);
        }
        for (Flag pending; (pending = track.pendingFlags.poll()) != null; ) {
            violations.flag(player, track, pending);
        }
        AntiCheatSettings now_ = settings;
        if (!now_.enabled()) {
            synchronized (track) {
                adopt(player, m, player.getLocation());
            }
            return;
        }
        boolean paused = Bukkit.getTPS()[0] < now_.minTps();
        synchronized (track) {
            for (MoveSample sample : drained) {
                if (sample.hasRotation()) {
                    rotated(player, track, sample);
                }
                if (sample.hasPosition()) {
                    moved(player, track, sample, paused);
                }
            }
            if (!paused) {
                judgeTimer(player, track, now_.timerLeniency());
            }
            settleVelocities(player, track, now);
            settleHits(player, track, now);
            settleSwings(player, track, now);
            judgeSprint(player, track, now);
            judgeInventory(player, track, now);
        }
    }

    /** A vanilla client lets go of every key the moment a screen opens. */
    private void judgeInventory(Player player, PlayerTrack track, long now) {
        PlayerTrack.Movement m = track.movement;
        long opened = track.world.containerOpenedMillis;
        if (opened == 0 || now - opened < 400L + track.compensated(settings.maxPing()) || !m.inputSeen || !(m.moving() || m.jump)
                || Double.isNaN(m.lastHd) || m.lastHd < 0.1 || m.special || player.isGliding() || player.isFlying()
                || player.isInsideVehicle() || track.exemption() != null || !run(track, CheckType.INVENTORY_MOVE)) {
            return;
        }
        if (track.buffer(CheckType.INVENTORY_MOVE, 3, 0.2).fail(1)) {
            ActionRule.Decision decision = violations.flag(player, track, Flag.of(CheckType.INVENTORY_MOVE,
                    String.format(Locale.ROOT, "walking at %.2f with a container open for %d ms", m.lastHd, now - opened)));
            if (decision.act()) {
                player.closeInventory();
            }
        }
    }

    /**
     * The ping lag compensation uses: the median round trip of our own pings when there are enough,
     * the keep-alive ping otherwise — and whether the two disagree the way a held-back keep-alive does.
     */
    private void measurePing(Player player, PlayerTrack track) {
        PacketTap probe = tap;
        if (probe != null) {
            probe.probe(player);
        }
        double[] trips = track.packets.roundTrips.toArray();
        int keepAlive = player.getPing();
        track.ping = trips.length >= de.raindancer.modules.anticheat.rules.PingRule.FEWEST
                ? (int) Math.round(pings.median(trips)) : keepAlive;
        Judgement spoof = pings.spoofed(keepAlive, trips);
        if (spoof.failed() && run(track, CheckType.PING_SPOOF)) {
            report(player, track, CheckType.PING_SPOOF, 1, 4, 0.5, spoof.reason());
        } else {
            track.buffer(CheckType.PING_SPOOF, 4, 0.5).pass();
        }
    }

    /** The packet thread fed the balance; whether it is past the line is decided here. */
    public void judgeTimer(Player player, PlayerTrack track, int leniency) {
        double balance = track.timer.balance();
        if (balance > leniency) {
            track.timer.settle();
            ActionRule.Decision decision = violations.flag(player, track, Flag.of(CheckType.TIMER,
                    Math.min(5, balance / 100.0), String.format(Locale.ROOT, "clock %.0f ms ahead", balance)));
            if (decision.act()) {
                setback(player, track);
            }
        }
    }

    // ------------------------------------------------------------------------------- moves

    private void moved(Player player, PlayerTrack track, MoveSample sample, boolean paused) {
        PlayerTrack.Movement m = track.movement;
        long now = track.now();
        track.recorder.record(new de.raindancer.modules.anticheat.model.ReplayFrame(now, player.getWorld().getName(),
                sample.x(), sample.y(), sample.z(), sample.hasRotation() ? sample.yaw() : m.yaw,
                sample.hasRotation() ? sample.pitch() : m.pitch, sample.onGround()));
        if (m.teleportTarget != null) {
            Location target = m.teleportTarget;
            boolean arrived = target.getWorld() != null && target.getWorld().equals(player.getWorld())
                    && distanceSquared(target, sample) < 0.25;
            if (arrived || now - m.teleportAtMillis > TELEPORT_PATIENCE_MILLIS) {
                m.teleportTarget = null;
                adopt(player, m, sample);
            }
            return;
        }
        World world = player.getWorld();
        if (!m.known || !world.getName().equals(m.worldName)) {
            adopt(player, m, sample);
            return;
        }
        if (!world.isChunkLoaded((int) Math.floor(sample.x()) >> 4, (int) Math.floor(sample.z()) >> 4)) {
            adopt(player, m, sample);
            return;
        }
        if (!track.packets.tapped && !paused) {
            track.timer.tick(sample.nanos());
        }

        double dx = sample.x() - m.x;
        double dy = sample.y() - m.y;
        double dz = sample.z() - m.z;
        double hd = Math.hypot(dx, dz);
        int ticks = Math.max(1, sample.ticks());
        Surroundings here = Surroundings.at(world, sample.x(), sample.y(), sample.z(), player.getWidth(), player.getHeight());
        // A block the client has not been told about yet is still there for it — mined away under its
        // feet by a vein miner, another player, an explosion. Its own word is all there is until then.
        boolean blocksInFlux = !Double.isNaN(m.x) && blocksInFlux(track, m, sample, player.getWidth(), player.getHeight(),
                2L * track.compensated(settings.maxPing()) + 500);
        if (blocksInFlux) {
            here = here.withGround(sample.onGround());
        } else {
            here = here.withGround(Physics.standing(here.ground(), dy, m.onGround,
                    attribute(player, Attribute.STEP_HEIGHT, Physics.STEP_HEIGHT)));
        }

        boolean free = freelyMoving(player, track) || paused;
        PlayerTrack.Exemption exemption = track.exemption();
        if (free || exemption != null) {
            remember(world, m, sample, dy, hd, here, true);
            return;
        }

        boolean failed = false;
        boolean nearRideable = false;

        // Phase: walking into solid blocks from outside them.
        if (here.inside() && !m.inside && !blocksInFlux && run(track, CheckType.PHASE)) {
            failed |= report(player, track, CheckType.PHASE, 1, 1, 0.1, String.format(Locale.ROOT,
                    "moved %.2f blocks into a solid block", Math.sqrt(hd * hd + dy * dy)));
        }

        boolean levitating = player.hasPotionEffect(PotionEffectType.LEVITATION);
        boolean gliding = player.isGliding();
        boolean specialNow = here.special() || m.special;
        double velocityY = pendingVelocity(track, true);
        double velocityH = pendingVelocity(track, false);

        if (gliding) {
            failed |= judgeGlide(player, track, dy, hd);
        } else if (!specialNow && !levitating && run(track, CheckType.FLY)) {
            double gravity = attribute(player, Attribute.GRAVITY, Physics.GRAVITY);
            double jump = Physics.jumpVelocity(attribute(player, Attribute.JUMP_STRENGTH, Physics.JUMP_VELOCITY),
                    level(player, PotionEffectType.JUMP_BOOST), m.jumpFactor);
            double step = attribute(player, Attribute.STEP_HEIGHT, Physics.STEP_HEIGHT);
            double bounce = m.bouncy && m.onGround ? m.fallSpeed + 0.1 : 0;
            VerticalRule.Move move = new VerticalRule.Move(m.lastDy, dy, ticks, m.onGround, here.ground(),
                    here.ceiling() || m.special, gravity, jump, step, velocityY, bounce);
            Judgement judged = vertical.judge(move);
            if (judged.failed() && player.hasPotionEffect(PotionEffectType.SLOW_FALLING)) {
                judged = vertical.judge(new VerticalRule.Move(m.lastDy, dy, ticks, m.onGround, here.ground(),
                        move.ceiling(), Math.min(gravity, Physics.SLOW_FALLING_GRAVITY), jump, step, velocityY, bounce));
            }
            if (judged.failed()) {
                nearRideable = Surroundings.nearRideableGround(world, sample.x(), sample.y(), sample.z());
                if (!nearRideable) {
                    CheckType which = here.overLiquid() && Math.abs(dy) < 0.05 ? CheckType.JESUS : CheckType.FLY;
                    failed |= report(player, track, which, 1, 1, 0.02, judged.reason());
                }
            } else {
                track.buffer(CheckType.FLY, 1, 0.02).pass();
            }
        }

        // NoFall: the client says it stands on something the server says is not there.
        if (sample.onGround() && !here.ground() && !specialNow && !here.overLiquid() && !gliding
                && run(track, CheckType.NO_FALL) && !track.isExempt(PlayerTrack.Exemption.BLOCK_UNDERFOOT)) {
            if (!nearRideable) {
                nearRideable = Surroundings.nearRideableGround(world, sample.x(), sample.y(), sample.z());
            }
            if (!nearRideable && track.buffer(CheckType.NO_FALL, 2, 0.05).fail(1)) {
                ActionRule.Decision decision = violations.flag(player, track, Flag.of(CheckType.NO_FALL,
                        String.format(Locale.ROOT, "claimed to stand while falling (dy %.2f, fallen %.1f)", dy, m.ourFallDistance)));
                if (decision.act()) {
                    // Vanilla deals the damage itself the next time it believes they landed.
                    player.setFallDistance(Math.max(player.getFallDistance(), m.ourFallDistance));
                }
            }
        }

        if (!gliding && !specialNow && !here.web() && run(track, CheckType.SPEED)) {
            failed |= judgeHorizontal(player, track, sample, hd, dy, ticks, here, velocityH);
        }

        // Air control: two ticks fully airborne, nothing to bump into, nothing pushing.
        if (!gliding && !specialNow && !m.onGround && !m.onGroundBefore && !here.ground() && Double.isNaN(velocityH)
                && !Double.isNaN(m.lastDx) && !sample.horizontalCollision() && !m.lastCollided && run(track, CheckType.STRAFE)) {
            Judgement steered = airControl.judge(m.lastDx, m.lastDz, dx, dz, ticks);
            if (steered.failed() && !nearWall(world, sample, player) && Surroundings.pushers(world, sample.x(), sample.y(), sample.z(), player) == 0) {
                failed |= report(player, track, CheckType.STRAFE, 1, 2, 0.04, steered.reason());
            } else if (steered.passed()) {
                track.buffer(CheckType.STRAFE, 2, 0.04).pass();
            }
        }

        // Ladders: no faster than vanilla climbs, unless something launched them.
        if (here.climbable() && m.special && dy > 0 && Double.isNaN(velocityY) && !levitating && run(track, CheckType.FLY)) {
            Judgement climbed = airControl.climb(dy / ticks);
            if (climbed.failed()) {
                failed |= report(player, track, CheckType.FLY, 1, 2, 0.05, climbed.reason());
            }
        }

        // Knockback is judged on the rise it caused.
        for (double[] velocity : m.velocities) {
            velocity[4] = Math.max(velocity[4], dy);
            if (here.ceiling() || here.special() || here.web()) {
                velocity[5] = 1;
            }
        }

        if (failed && settings.setbacks()) {
            setback(player, track);
            return;
        }
        remember(world, m, sample, dy, hd, here, false);
    }

    private static boolean blocksInFlux(PlayerTrack track, PlayerTrack.Movement m, MoveSample sample, double width, double height,
                                        long patienceMillis) {
        double half = width / 2 + 0.1;
        return track.packets.blocks.uncertain(
                (int) Math.floor(Math.min(m.x, sample.x()) - half), (int) Math.floor(Math.min(m.y, sample.y()) - 1),
                (int) Math.floor(Math.min(m.z, sample.z()) - half), (int) Math.floor(Math.max(m.x, sample.x()) + half),
                (int) Math.floor(Math.max(m.y, sample.y()) + height), (int) Math.floor(Math.max(m.z, sample.z()) + half),
                track.now(), patienceMillis);
    }

    private boolean judgeHorizontal(Player player, PlayerTrack track, MoveSample sample, double hd, double dy,
                                    int ticks, Surroundings here, double velocityH) {
        PlayerTrack.Movement m = track.movement;
        ItemStack using = player.isHandRaised() ? player.getActiveItem() : null;
        UseEffects effects = using == null || using.getType() == Material.AIR ? null
                : using.getDataOrDefault(DataComponentTypes.USE_EFFECTS, null);
        double useSlowdown = using == null ? 1 : effects == null ? Physics.USING_ITEM_MULTIPLIER : effects.speedMultiplier();
        boolean useMaySprint = using == null || effects != null && effects.canSprint();
        boolean sneaking = player.isSneaking() || m.sneak;
        double sneakSlowdown = sneaking ? attribute(player, Attribute.SNEAKING_SPEED, 0.3) : 1;
        boolean maySprint = (player.getFoodLevel() > 6 || player.getAllowFlight()) && !sneaking && useMaySprint
                && !player.hasPotionEffect(PotionEffectType.BLINDNESS);
        double speed = attribute(player, Attribute.MOVEMENT_SPEED, 0.1);
        boolean jumped = m.onGround && dy > 0.1;
        HorizontalRule.Move move = new HorizontalRule.Move(m.lastHd, hd, ticks, m.onGround, m.onGroundBefore,
                m.friction, m.frictionBefore, speed, player.isSprinting(), maySprint, useSlowdown * sneakSlowdown,
                jumped, velocityH, 0);
        HorizontalRule.Result result = horizontal.judge(move);
        if (result.failed()) {
            int pushers = Surroundings.pushers(player.getWorld(), sample.x(), sample.y(), sample.z(), player);
            if (pushers > 0) {
                result = horizontal.judge(new HorizontalRule.Move(m.lastHd, hd, ticks, m.onGround, m.onGroundBefore,
                        m.friction, m.frictionBefore, speed, player.isSprinting(), maySprint,
                        useSlowdown * sneakSlowdown, jumped, velocityH, 0.05 * Math.min(3, pushers)));
            }
        }
        if (m.sprintHitThisTick && result.passed() && run(track, CheckType.KEEP_SPRINT) && m.onGround) {
            HorizontalRule.Move slowed = new HorizontalRule.Move(m.lastHd * 0.6, hd, ticks, m.onGround, m.onGroundBefore,
                    m.friction, m.frictionBefore, speed / Physics.SPRINT_MULTIPLIER, false, false, 1, false, velocityH, 0.02);
            if (horizontal.judge(slowed).failed()) {
                report(player, track, CheckType.KEEP_SPRINT, 1, 4, 0.2, String.format(Locale.ROOT,
                        "full speed %.3f right after a sprinting hit", hd));
            }
        }
        m.sprintHitThisTick = false;
        if (result.passed()) {
            track.buffer(CheckType.SPEED, 2, 0.04).pass();
            track.buffer(CheckType.NO_SLOW, 3, 0.05).pass();
            return false;
        }
        if (result.outcome() == HorizontalRule.Outcome.NO_SLOW) {
            if (!run(track, CheckType.NO_SLOW)) {
                return false;
            }
            return report(player, track, CheckType.NO_SLOW, 1, 3, 0.05, String.format(Locale.ROOT,
                    "%.3f while %s, at most %.3f", hd, using != null ? "using " + using.getType().name().toLowerCase(Locale.ROOT) : "sneaking",
                    result.limit()));
        }
        return report(player, track, CheckType.SPEED, Math.min(3, 1 + result.offset() * 4), 2, 0.04,
                String.format(Locale.ROOT, "%.3f blocks/tick, at most %.3f", hd, result.limit()));
    }

    private boolean judgeGlide(Player player, PlayerTrack track, double dy, double hd) {
        PlayerTrack.Movement m = track.movement;
        if (!run(track, CheckType.ELYTRA) || track.isExempt(PlayerTrack.Exemption.ELYTRA)) {
            m.glideClimbTicks = 0;
            return false;
        }
        boolean climbingSlowly = dy > 0.01 && hd < 0.35;
        boolean hovering = Math.abs(dy) < 0.01 && hd < 0.1;
        m.glideClimbTicks = climbingSlowly || hovering ? m.glideClimbTicks + 1 : 0;
        if (m.glideClimbTicks > 8) {
            m.glideClimbTicks = 0;
            return report(player, track, CheckType.ELYTRA, 1, 0, 0.1, String.format(Locale.ROOT,
                    "%s on an elytra without a firework (dy %.3f, %.3f blocks/tick)", hovering ? "hovering" : "climbing", dy, hd));
        }
        return false;
    }

    /** Raises a buffered flag. @return whether the move should be undone */
    private boolean report(Player player, PlayerTrack track, CheckType check, double weight, double limit,
                           double forgiveness, String detail) {
        Buffer buffer = track.buffer(check, limit, forgiveness);
        if (!buffer.fail(1)) {
            return false;
        }
        ActionRule.Decision decision = violations.flag(player, track, Flag.of(check, weight, detail));
        return decision.act() && check.action() == CheckType.Action.SETBACK;
    }

    private void remember(World world, PlayerTrack.Movement m, MoveSample sample, double dy, double hd, Surroundings here, boolean exempt) {
        boolean wasGround = m.onGround;
        m.lastDx = exempt ? Double.NaN : sample.x() - m.x;
        m.lastDz = exempt ? Double.NaN : sample.z() - m.z;
        m.lastCollided = sample.horizontalCollision();
        m.x = sample.x();
        m.y = sample.y();
        m.z = sample.z();
        m.lastDy = exempt ? Double.NaN : dy;
        m.lastHd = exempt ? Double.NaN : hd;
        m.onGroundBefore = wasGround;
        m.onGround = here.ground();
        m.frictionBefore = m.friction;
        m.friction = here.friction();
        m.inside = here.inside();
        m.special = here.special();
        m.bouncy = here.bouncy();
        m.rises.add(dy);
        if (here.ground() || here.special()) {
            if (!here.bouncy()) {
                m.fallSpeed = 0;
            }
            m.ourFallDistance = 0;
            m.airTicks = 0;
            m.highestY = sample.y();
        } else {
            m.airTicks++;
            m.highestY = Double.isNaN(m.highestY) ? sample.y() : Math.max(m.highestY, sample.y());
            if (dy < 0) {
                m.ourFallDistance += (float) -dy;
                m.fallSpeed = Math.max(m.fallSpeed, -Physics.nextVertical(dy, Physics.GRAVITY));
            }
        }
        m.jumpFactor = here.jumpFactor();
        if (!exempt && here.ground() && !here.inside() && !here.special()) {
            m.legit = new Location(world, sample.x(), sample.y(), sample.z());
        }
    }

    /** Takes a position as the new starting point without judging how they got there. */
    private void adopt(Player player, PlayerTrack.Movement m, MoveSample sample) {
        adopt(player, m, new Location(player.getWorld(), sample.x(), sample.y(), sample.z()));
    }

    private void adopt(Player player, PlayerTrack.Movement m, Location at) {
        m.forget();
        m.known = true;
        m.worldName = player.getWorld().getName();
        m.x = at.getX();
        m.y = at.getY();
        m.z = at.getZ();
        Surroundings here = Surroundings.at(player.getWorld(), at.getX(), at.getY(), at.getZ(), player.getWidth(), player.getHeight());
        m.onGround = here.ground();
        m.onGroundBefore = here.ground();
        m.friction = here.friction();
        m.frictionBefore = here.friction();
        m.inside = here.inside();
        m.special = here.special();
        m.bouncy = here.bouncy();
        m.jumpFactor = here.jumpFactor();
        m.ourFallDistance = 0;
        if (here.ground() && !here.inside()) {
            m.legit = new Location(player.getWorld(), at.getX(), at.getY(), at.getZ());
        } else if (m.legit == null || m.legit.getWorld() == null || !m.legit.getWorld().equals(player.getWorld())) {
            m.legit = new Location(player.getWorld(), at.getX(), at.getY(), at.getZ());
        }
    }

    /** Puts them back where they last moved legitimately, at most five times a second. */
    public void setback(Player player, PlayerTrack track) {
        PlayerTrack.Movement m = track.movement;
        long now = track.now();
        if (now - m.lastSetbackMillis < 200 || m.legit == null || m.legit.getWorld() == null
                || !m.legit.getWorld().equals(player.getWorld())) {
            return;
        }
        m.lastSetbackMillis = now;
        Location back = m.legit.clone();
        back.setYaw(player.getLocation().getYaw());
        back.setPitch(player.getLocation().getPitch());
        m.forget();
        m.teleportTarget = back;
        m.teleportAtMillis = now;
        player.setVelocity(new Vector());
        player.teleportAsync(back, PlayerTeleportEvent.TeleportCause.PLUGIN);
    }

    // ---------------------------------------------------------------------------- rotations

    private void rotated(Player player, PlayerTrack track, MoveSample sample) {
        PlayerTrack.Combat c = track.combat;
        c.rotations.addLast(new float[]{sample.yaw(), sample.pitch()});
        while (c.rotations.size() > 5) {
            c.rotations.removeFirst();
        }
        if (!sample.fromPacket() || !run(track, CheckType.AIM)) {
            c.lastRawYaw = sample.yaw();
            return;
        }
        if (!Float.isNaN(c.lastRawYaw)) {
            double rawDelta = sample.yaw() - c.lastRawYaw;
            Judgement wrapped = aim.wrapped(c.lastRawYawDelta, rawDelta);
            if (wrapped.failed() && track.exemption() == null && !player.isInsideVehicle()) {
                report(player, track, CheckType.AIM, 1, 2, 0.01, wrapped.reason());
            }
            c.lastRawYawDelta = rawDelta;
        }
        c.lastRawYaw = sample.yaw();
        float previousPitch = track.movement.pitch;
        track.movement.yaw = sample.yaw();
        track.movement.pitch = sample.pitch();
        double pitchDelta = sample.pitch() - previousPitch;
        boolean spyglass = player.isHandRaised() && player.getActiveItem().getType() == Material.SPYGLASS;
        if (Math.abs(pitchDelta) > 1e-4 && Math.abs(pitchDelta) < 10 && Math.abs(sample.pitch()) < 89 && Math.abs(previousPitch) < 89 && !spyglass
                && !player.isInsideVehicle()) {
            c.pitchDeltas.add(pitchDelta);
            if (c.pitchDeltas.full()) {
                Judgement steps = aim.noSensitivityStep(c.pitchDeltas.toArray());
                c.pitchDeltas.clear();
                if (steps.failed()) {
                    report(player, track, CheckType.AIM, 1, 2, 0.05, steps.reason());
                }
            }
        }
    }

    // --------------------------------------------------------------------- waiting checks

    private void settleVelocities(Player player, PlayerTrack track, long now) {
        PlayerTrack.Movement m = track.movement;
        long window = Math.min(1500, track.compensated(settings.maxPing()) * 2L + 500);
        for (Iterator<double[]> it = m.velocities.iterator(); it.hasNext(); ) {
            double[] v = it.next();
            // Confirmed by the client's pong: the push is applied by now, so a few ticks settle it.
            boolean confirmed = v.length > 7 && v[7] > 0;
            if (confirmed ? now - (long) v[7] < 250 : now - (long) v[3] < window) {
                continue;
            }
            it.remove();
            if (v[5] > 0 || track.exemption() != null || freelyMoving(player, track)
                    || !run(track, CheckType.VELOCITY)) {
                continue;
            }
            Judgement taken = motion.knockbackTaken(v[1], v[4]);
            if (taken.failed()) {
                report(player, track, CheckType.VELOCITY, 1, 1, 0.1, taken.reason());
            } else {
                track.buffer(CheckType.VELOCITY, 1, 0.1).pass();
            }
        }
    }

    private void settleHits(Player player, PlayerTrack track, long now) {
        PlayerTrack.Combat c = track.combat;
        while (!c.pendingHits.isEmpty() && now - c.pendingHits.peekFirst().atMillis() >= 60) {
            PlayerTrack.PendingHit hit = c.pendingHits.removeFirst();
            if (!run(track, CheckType.HITBOX)) {
                continue;
            }
            List<CombatRule.Rotation> rotations = new ArrayList<>();
            for (float[] rotation : c.rotations) {
                rotations.add(new CombatRule.Rotation(rotation[0], rotation[1]));
            }
            Location here = player.getLocation();
            rotations.add(new CombatRule.Rotation(here.getYaw(), here.getPitch()));
            Judgement judged = combat.hitbox(hit.eyes(), rotations, hit.boxes(), 0.1 + hit.margin(), 64);
            if (judged.failed()) {
                report(player, track, CheckType.HITBOX, 1, 2, 0.1, judged.reason() + " (" + hit.targetName() + ")");
            } else {
                track.buffer(CheckType.HITBOX, 2, 0.1).pass();
            }
        }
    }

    private void settleSwings(Player player, PlayerTrack track, long now) {
        PlayerTrack.Combat c = track.combat;
        while (!c.unswung.isEmpty() && now - c.unswung.peekFirst() > 160) {
            c.unswung.removeFirst();
            if (run(track, CheckType.NO_SWING)) {
                report(player, track, CheckType.NO_SWING, 1, 2, 0.2, "attacked without swinging");
            }
        }
    }

    private void judgeSprint(Player player, PlayerTrack track, long now) {
        PlayerTrack.Movement m = track.movement;
        if (!player.isSprinting() || player.isFlying() || player.isSwimming() || player.isGliding()
                || player.isInsideVehicle() || freelyMoving(player, track) || !run(track, CheckType.SPRINT)
                || track.exemption() != null) {
            track.buffer(CheckType.SPRINT, 4, 0.2).pass();
            return;
        }
        String why = null;
        if (m.inputSeen && !m.forward && now - m.inputChangedMillis > 200 + track.compensated(settings.maxPing())) {
            why = m.backward ? "sprinting backwards" : "sprinting without moving forward";
        } else if (player.getFoodLevel() <= 6 && player.getGameMode() == GameMode.SURVIVAL && !player.getAllowFlight()) {
            why = "sprinting while starving";
        } else if (player.isHandRaised()) {
            ItemStack using = player.getActiveItem();
            UseEffects effects = using.getDataOrDefault(DataComponentTypes.USE_EFFECTS, null);
            if (effects == null || !effects.canSprint()) {
                why = "sprinting while using " + using.getType().name().toLowerCase(Locale.ROOT);
            }
        }
        if (why == null) {
            track.buffer(CheckType.SPRINT, 4, 0.2).pass();
            return;
        }
        Buffer buffer = track.buffer(CheckType.SPRINT, 4, 0.2);
        if (buffer.fail(1)) {
            ActionRule.Decision decision = violations.flag(player, track, Flag.of(CheckType.SPRINT, why));
            if (decision.act()) {
                player.setSprinting(false);
            }
        }
    }

    // -------------------------------------------------------------------------- helpers

    /** Whether a wall stands within a hair of their box — bumping into it changes course legitimately. */
    private static boolean nearWall(World world, MoveSample sample, Player player) {
        double half = player.getWidth() / 2 + 0.05;
        return world.hasCollisionsIn(new BoundingBox(sample.x() - half, sample.y() + 0.01, sample.z() - half,
                sample.x() + half, sample.y() + player.getHeight(), sample.z() + half));
    }

    private void recordPosition(Player player, PlayerTrack track, long now) {
        Location at = player.getLocation();
        PlayerTrack.Combat c = track.combat;
        c.history.addLast(new double[]{now, at.getX(), at.getY(), at.getZ(), player.getWidth(), player.getHeight()});
        while (c.history.size() > PlayerTrack.POSITION_HISTORY) {
            c.history.removeFirst();
        }
    }

    /** Where this player's box was over the last {@code millis}, newest last; never empty. */
    public static List<BoundingBox> boxesWithin(PlayerTrack track, long now, long millis, BoundingBox current) {
        List<BoundingBox> boxes = new ArrayList<>();
        synchronized (track) {
            for (double[] h : track.combat.history) {
                if (now - (long) h[0] <= millis) {
                    double half = h[4] / 2;
                    boxes.add(new BoundingBox(h[1] - half, h[2], h[3] - half, h[1] + half, h[2] + h[5], h[3] + half));
                }
            }
        }
        boxes.add(current);
        return boxes;
    }

    private void refreshPermissions(Player player, PlayerTrack track) {
        track.bypassAll = player.hasPermission(PermissionNodes.BYPASS);
        for (CheckType check : CheckType.values()) {
            if (player.hasPermission(PermissionNodes.BYPASS_PREFIX + check.key())) {
                track.bypassed.add(check);
            } else {
                track.bypassed.remove(check);
            }
        }
        track.bedrock = Tracks.isBedrock(player.getUniqueId());
    }

    /** States the movement checks do not model at all. */
    private static boolean freelyMoving(Player player, PlayerTrack track) {
        GameMode mode = player.getGameMode();
        return mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR || player.isFlying()
                || player.isInsideVehicle() || player.isDead() || player.isSleeping() || player.isRiptiding()
                || player.isClimbing() && player.isSneaking();
    }

    private boolean run(PlayerTrack track, CheckType check) {
        return violations.runs(track, check);
    }

    private static double pendingVelocity(PlayerTrack track, boolean vertical) {
        double best = Double.NaN;
        for (double[] v : track.movement.velocities) {
            double value = vertical ? v[1] : Math.hypot(v[0], v[2]);
            best = Double.isNaN(best) ? value : Math.max(best, value);
        }
        return best;
    }

    private static double attribute(Player player, Attribute attribute, double fallback) {
        AttributeInstance instance = player.getAttribute(attribute);
        return instance == null ? fallback : instance.getValue();
    }

    private static int level(Player player, PotionEffectType type) {
        PotionEffect effect = player.getPotionEffect(type);
        return effect == null ? 0 : effect.getAmplifier() + 1;
    }

    private static double distanceSquared(Location target, MoveSample sample) {
        double dx = target.getX() - sample.x();
        double dy = target.getY() - sample.y();
        double dz = target.getZ() - sample.z();
        return dx * dx + dy * dy + dz * dz;
    }

    @Override
    public void settings(AntiCheatSettings fresh) {
        this.settings = fresh == null ? AntiCheatSettings.DEFAULTS : fresh;
    }

    @Override
    public String describe() {
        return "judging every reported move once a tick, on the player's own thread";
    }
}
