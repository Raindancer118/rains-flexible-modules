package de.raindancer.modules.anticheat.service;

import de.raindancer.modules.anticheat.AntiCheatSettings;
import de.raindancer.modules.anticheat.model.Buffer;
import de.raindancer.modules.anticheat.model.CheckType;
import de.raindancer.modules.anticheat.model.Flag;
import de.raindancer.modules.anticheat.model.PlayerTrack;
import de.raindancer.modules.anticheat.rules.ActionRule;
import de.raindancer.modules.anticheat.rules.BreakRule;
import de.raindancer.modules.anticheat.rules.CombatRule;
import de.raindancer.modules.anticheat.rules.Geometry;
import de.raindancer.modules.anticheat.rules.Judgement;
import de.raindancer.modules.anticheat.rules.PlaceRule;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/** Digging, placing and using blocks. Each returns whether the event should be cancelled. */
public final class WorldService implements IAntiCheatService {

    private final Tracks tracks;
    private final ViolationService violations;
    private final BreakRule breaking = new BreakRule();
    private final PlaceRule placing = new PlaceRule();
    private volatile AntiCheatSettings settings = AntiCheatSettings.DEFAULTS;

    public WorldService(Tracks tracks, ViolationService violations) {
        this.tracks = tracks;
        this.violations = violations;
    }

    public boolean startDigging(Player player, Block block) {
        PlayerTrack track = tracks.of(player);
        long now = track.now();
        boolean cancel = false;
        synchronized (track) {
            PlayerTrack.World w = track.world;
            w.digX = block.getX();
            w.digY = block.getY();
            w.digZ = block.getZ();
            w.digStartMillis = now;
            w.digSpeedAtStart = block.getBreakSpeed(player);
            if (!track.packets.tapped) {
                Deque<Long> starts = w.starts;
                starts.addLast(now);
                while (!starts.isEmpty() && now - starts.peekFirst() > 50) {
                    starts.removeFirst();
                }
                if (starts.size() >= 4) {
                    cancel = flag(player, track, CheckType.NUKER, 2, 0, starts.size() + " blocks started within 50 ms");
                }
            }
        }
        return cancel;
    }

    public void stopDigging(Player player) {
        PlayerTrack track = tracks.of(player);
        synchronized (track) {
            track.world.stopDigging();
        }
    }

    public boolean broke(Player player, Block block) {
        PlayerTrack track = tracks.of(player);
        long now = track.now();
        boolean cancel = false;
        boolean digged;
        long elapsed;
        double speedAtStart;
        synchronized (track) {
            PlayerTrack.World w = track.world;
            digged = w.digging() && w.digX == block.getX() && w.digY == block.getY() && w.digZ == block.getZ();
            elapsed = now - w.digStartMillis;
            speedAtStart = w.digSpeedAtStart;
            if (digged) {
                w.stopDigging();
                w.lastBreakMillis = now;
            }
        }
        if (!digged) {
            // A second block from the same swing is some plugin's chain (vein mining, tree felling).
            return false;
        }
        Location feet = player.getLocation();
        if (block.getY() < feet.getY() && block.getY() >= feet.getY() - 1.5
                && Math.abs(block.getX() + 0.5 - feet.getX()) < 1.3 && Math.abs(block.getZ() + 0.5 - feet.getZ()) < 1.3) {
            track.exempt(PlayerTrack.Exemption.BLOCK_UNDERFOOT, 600);
        }
        boolean paused = Bukkit.getTPS()[0] < settings.minTps();
        if (player.getGameMode() != GameMode.CREATIVE && !paused && violations.runs(track, CheckType.FAST_BREAK)) {
            double speed = Math.max(speedAtStart, block.getBreakSpeed(player));
            int expected = breaking.expectedTicks(speed);
            Judgement blatant = breaking.blatant(elapsed, expected);
            if (blatant.failed()) {
                cancel |= flag(player, track, CheckType.FAST_BREAK, 2, 0, blatant.reason() + " (" + name(block.getType()) + ")");
            } else {
                double shortfall = breaking.shortfall(elapsed, expected, 50 + track.compensated(settings.maxPing()) / 4.0);
                boolean over;
                synchronized (track) {
                    PlayerTrack.World w = track.world;
                    w.fastBreakBalance = shortfall > 0 ? w.fastBreakBalance + shortfall : w.fastBreakBalance * 0.9;
                    over = w.fastBreakBalance > 1000;
                    if (over) {
                        w.fastBreakBalance = 0;
                    }
                }
                if (over) {
                    cancel |= flag(player, track, CheckType.FAST_BREAK, 1, 0, String.format(Locale.ROOT,
                            "%s in %d ms of %d, a second ahead over the last blocks", name(block.getType()), elapsed, expected * 50));
                }
            }
        }
        cancel |= judgeReach(player, track, block.getBoundingBox().getVolume() > 0 ? block.getBoundingBox() : cube(block), "broke " + name(block.getType()));
        if (violations.runs(track, CheckType.GHOST_HAND) && !canSee(player, block)) {
            cancel |= flag(player, track, CheckType.GHOST_HAND, 1, 1, "broke " + name(block.getType()) + " behind a wall");
        }
        return cancel;
    }

    public boolean placed(Player player, Block placed, Block against, BlockState replaced) {
        PlayerTrack track = tracks.of(player);
        long now = track.now();
        boolean cancel = false;
        if (player.getGameMode() == GameMode.SPECTATOR) {
            return true;
        }
        synchronized (track) {
            Deque<Long> places = track.world.places;
            places.addLast(now);
            while (!places.isEmpty() && now - places.peekFirst() > 1000) {
                places.removeFirst();
            }
            int burst = 0;
            for (long at : places) {
                if (now - at <= 50) {
                    burst++;
                }
            }
            if (player.getGameMode() != GameMode.CREATIVE && (places.size() > 20 || burst >= 4)
                    && violations.runs(track, CheckType.FAST_PLACE)) {
                cancel |= flag(player, track, CheckType.FAST_PLACE, 1, 1,
                        places.size() + " blocks in a second, " + burst + " in one tick");
            }
        }
        if (!violations.runs(track, CheckType.SCAFFOLD)) {
            return cancel;
        }
        boolean sameSpot = against.getX() == placed.getX() && against.getY() == placed.getY() && against.getZ() == placed.getZ();
        Material was = replaced.getType();
        boolean onWater = placed.getType() == Material.LILY_PAD || placed.getType() == Material.FROGSPAWN;
        Judgement nothing = placing.againstNothing(sameSpot, was.isAir(), (was == Material.WATER || was == Material.LAVA) && !onWater);
        if (nothing.failed()) {
            cancel |= flag(player, track, CheckType.SCAFFOLD, 2, 0, nothing.reason());
            return cancel;
        }
        if (sameSpot) {
            return cancel;
        }
        BlockFace face = against.getFace(placed);
        if (face == null) {
            return cancel;
        }
        BoundingBox cube = cube(against);
        BoundingBox shape = against.getBoundingBox();
        if (shape.getVolume() <= 0) {
            shape = cube;
        }
        boolean full = Math.abs(shape.getVolume() - 1) < 1e-6;
        double moved;
        synchronized (track) {
            double hd = Double.isNaN(track.movement.lastHd) ? 0.4 : track.movement.lastHd;
            double dy = Double.isNaN(track.movement.lastDy) ? 0.6 : Math.abs(track.movement.lastDy);
            moved = hd + dy;
        }
        Vector eye = player.getEyeLocation().toVector();
        Judgement visible = placing.faceVisible(eye, cube, shape, full, face.getModX(), face.getModY(), face.getModZ(),
                0.1 + moved + (player.isSneaking() ? 0.35 : 0));
        if (visible.failed()) {
            cancel |= flag(player, track, CheckType.SCAFFOLD, 1, 1, visible.reason());
        }
        List<CombatRule.Rotation> rotations = new ArrayList<>();
        synchronized (track) {
            for (float[] rotation : track.combat.rotations) {
                rotations.add(new CombatRule.Rotation(rotation[0], rotation[1]));
            }
        }
        Location look = player.getLocation();
        rotations.add(new CombatRule.Rotation(look.getYaw(), look.getPitch()));
        boolean looked = false;
        BoundingBox around = cube.clone().expand(0.5);
        for (CombatRule.Rotation rotation : rotations) {
            if (Geometry.rayHits(eye, Geometry.direction(rotation.yaw(), rotation.pitch()), around, 8)) {
                looked = true;
                break;
            }
        }
        if (!looked) {
            cancel |= flag(player, track, CheckType.SCAFFOLD, 1, 2, "placed against " + name(against.getType()) + " without looking at it");
        }
        cancel |= judgeReach(player, track, shape, "placed against " + name(against.getType()));
        return cancel;
    }

    /** Opening a container or using a block. @return whether the use should be refused */
    public boolean used(Player player, Block block) {
        PlayerTrack track = tracks.of(player);
        synchronized (track) {
            track.combat.lastUseMillis = track.now();
        }
        if (player.getGameMode() == GameMode.SPECTATOR) {
            return false;
        }
        boolean cancel = judgeReach(player, track, block.getBoundingBox().getVolume() > 0 ? block.getBoundingBox() : cube(block),
                "used " + name(block.getType()));
        if (isContainer(block.getType()) && violations.runs(track, CheckType.GHOST_HAND) && !canSee(player, block)) {
            cancel |= flag(player, track, CheckType.GHOST_HAND, 1, 0, "opened " + name(block.getType()) + " through a wall");
        }
        return cancel;
    }

    /** Remembers placements another plugin refused: the client briefly sees a block that is not there. */
    public void refusedPlacement(Player player) {
        tracks.of(player).exempt(PlayerTrack.Exemption.BLOCK_UNDERFOOT, 1000);
    }

    private boolean judgeReach(Player player, PlayerTrack track, BoundingBox box, String what) {
        if (!violations.runs(track, CheckType.BLOCK_REACH)) {
            return false;
        }
        double range = attribute(player, Attribute.BLOCK_INTERACTION_RANGE, player.getGameMode() == GameMode.CREATIVE ? 5.0 : 4.5);
        double distance = Geometry.distance(player.getEyeLocation().toVector(), box);
        double slack = 0.5;
        synchronized (track) {
            if (!Double.isNaN(track.movement.lastHd)) {
                slack += track.movement.lastHd + Math.abs(track.movement.lastDy);
            }
        }
        if (distance > range + slack) {
            return flag(player, track, CheckType.BLOCK_REACH, 1, 1, String.format(Locale.ROOT, "%s %.2f blocks away, range %.1f", what, distance, range));
        }
        return false;
    }

    /** Whether any straight line from the eye reaches the block before another solid one. */
    private static boolean canSee(Player player, Block block) {
        Location eye = player.getEyeLocation();
        BoundingBox box = cube(block);
        Vector center = box.getCenter();
        List<Vector> points = new ArrayList<>();
        points.add(center);
        for (BlockFace face : List.of(BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            points.add(center.clone().add(new Vector(face.getModX() * 0.49, face.getModY() * 0.49, face.getModZ() * 0.49)));
        }
        for (Vector point : points) {
            Vector to = point.clone().subtract(eye.toVector());
            double distance = to.length();
            if (distance < 0.5) {
                return true;
            }
            RayTraceResult hit = player.getWorld().rayTraceBlocks(eye, to.normalize(), distance + 0.6,
                    FluidCollisionMode.NEVER, true);
            if (hit == null || hit.getHitBlock() == null || hit.getHitBlock().equals(block)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isContainer(Material type) {
        String name = type.name();
        return name.endsWith("CHEST") || name.endsWith("SHULKER_BOX") || name.equals("BARREL") || name.endsWith("FURNACE")
                || name.equals("SMOKER") || name.equals("HOPPER") || name.equals("DROPPER") || name.equals("DISPENSER")
                || name.equals("BREWING_STAND") || name.equals("CRAFTER");
    }

    private static BoundingBox cube(Block block) {
        return new BoundingBox(block.getX(), block.getY(), block.getZ(), block.getX() + 1, block.getY() + 1, block.getZ() + 1);
    }

    private static String name(Material type) {
        return type.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private boolean flag(Player player, PlayerTrack track, CheckType check, double weight, double limit, String detail) {
        Buffer buffer = track.buffer(check, limit, 0.2);
        if (!buffer.fail(1)) {
            return false;
        }
        ActionRule.Decision decision = violations.flag(player, track, Flag.of(check, weight, detail));
        return decision.act() && check.action() == CheckType.Action.CANCEL;
    }

    private static double attribute(Player player, Attribute attribute, double fallback) {
        AttributeInstance instance = player.getAttribute(attribute);
        return instance == null ? fallback : instance.getValue();
    }

    @Override
    public void settings(AntiCheatSettings fresh) {
        this.settings = fresh == null ? AntiCheatSettings.DEFAULTS : fresh;
    }

    @Override
    public String describe() {
        return "judging digging, placing and using blocks";
    }
}
