package de.raindancer.modules.anticheat.service;

import de.raindancer.modules.anticheat.AntiCheatSettings;
import de.raindancer.modules.anticheat.model.Buffer;
import de.raindancer.modules.anticheat.model.CheckType;
import de.raindancer.modules.anticheat.model.Flag;
import de.raindancer.modules.anticheat.model.PlayerTrack;
import de.raindancer.modules.anticheat.rules.ActionRule;
import de.raindancer.modules.anticheat.rules.CombatRule;
import de.raindancer.modules.anticheat.rules.Judgement;
import de.raindancer.modules.anticheat.rules.MotionRule;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.AttackRange;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.ComplexEntityPart;
import org.bukkit.entity.ComplexLivingEntity;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Every melee attack a player makes, judged on the attacker's own thread before it lands. */
public final class CombatService implements IAntiCheatService {

    private final Tracks tracks;
    private final ViolationService violations;
    private final CombatRule rule = new CombatRule();
    private final MotionRule motion = new MotionRule();
    private volatile AntiCheatSettings settings = AntiCheatSettings.DEFAULTS;

    public CombatService(Tracks tracks, ViolationService violations) {
        this.tracks = tracks;
        this.violations = violations;
    }

    /** @return whether the attack should not happen */
    public boolean attack(Player attacker, Entity target) {
        PlayerTrack track = tracks.of(attacker);
        long now = track.now();
        boolean cancel = false;
        synchronized (track) {
            PlayerTrack.Combat c = track.combat;
            c.lastAttackMillis = now;
            if (attacker.isSprinting()) {
                track.movement.sprintHitThisTick = true;
                track.movement.sprintHitMillis = now;
            }
            if (!track.packets.sendsTickEnd) {
                c.unswung.addLast(now);
                if (c.targetsTickStamp == 0 || now - c.targetsTickStamp > 50) {
                    c.targetsThisTick.clear();
                    c.targetsTickStamp = now;
                }
                c.targetsThisTick.add(target.getEntityId());
                if (c.targetsThisTick.size() >= 3) {
                    cancel |= flag(attacker, track, CheckType.MULTI_AURA, 2, 0, c.targetsThisTick.size() + " targets within 50 ms");
                }
            }
        }
        if (attacker.getGameMode() == GameMode.SPECTATOR) {
            return true;
        }

        String invalid = invalidAttack(attacker);
        if (invalid != null) {
            cancel |= flag(attacker, track, CheckType.INVALID_ATTACK, 1, 1, invalid);
        }

        if (target instanceof ComplexEntityPart || target instanceof ComplexLivingEntity) {
            return cancel;
        }
        boolean creative = attacker.getGameMode() == GameMode.CREATIVE;
        ItemStack held = attacker.getInventory().getItemInMainHand();
        AttackRange range = held.isEmpty() ? null : held.getDataOrDefault(DataComponentTypes.ATTACK_RANGE, null);
        double reach = range != null ? (creative ? range.maxCreativeReach() : range.maxReach())
                : attribute(attacker, Attribute.ENTITY_INTERACTION_RANGE, creative ? 5.0 : 3.0);
        double margin = range == null ? 0 : range.hitboxMargin();

        List<Vector> eyes = eyes(attacker, track, now);
        List<BoundingBox> boxes = boxes(target, now, track.ping);
        if (margin > 0) {
            boxes.replaceAll(box -> box.clone().expand(margin));
        }
        boolean timingTrusted = track.ping <= settings.maxPing();

        if (timingTrusted && violations.runs(track, CheckType.REACH)) {
            Judgement reached = rule.reach(eyes, boxes, reach, settings.reachLeniency());
            if (reached.failed()) {
                cancel |= flag(attacker, track, CheckType.REACH, Math.min(4, 1 + reached.offset() * 2), 1,
                        reached.reason() + " (" + target.getName() + ")");
            } else {
                track.buffer(CheckType.REACH, 1, 0.25).pass();
            }
        }

        if (violations.runs(track, CheckType.WALL_HIT)) {
            String wall = throughWall(attacker, target);
            if (wall != null) {
                cancel |= flag(attacker, track, CheckType.WALL_HIT, 1, 1, wall);
            } else {
                track.buffer(CheckType.WALL_HIT, 1, 0.2).pass();
            }
        }

        if (timingTrusted) {
            synchronized (track) {
                track.combat.pendingHits.addLast(new PlayerTrack.PendingHit(now, target.getName(), eyes, boxes, reach, margin));
                while (track.combat.pendingHits.size() > 10) {
                    track.combat.pendingHits.removeFirst();
                }
            }
        }
        return cancel;
    }

    /** A critical hit, after the attack went through. @return whether the extra damage should be taken away */
    public boolean critical(Player attacker) {
        PlayerTrack track = tracks.of(attacker);
        if (!violations.runs(track, CheckType.CRITICALS) || track.exemption() != null) {
            return false;
        }
        Location at = attacker.getLocation();
        double half = attacker.getWidth() / 2;
        boolean nearGround = attacker.getWorld().hasCollisionsIn(new BoundingBox(at.getX() - half, at.getY() - 0.15,
                at.getZ() - half, at.getX() + half, at.getY(), at.getZ() + half));
        double rise;
        double fall;
        synchronized (track) {
            rise = 0;
            for (double dy : track.movement.rises.toArray()) {
                rise = Math.max(rise, dy);
            }
            fall = Double.isNaN(track.movement.highestY) ? 0 : track.movement.highestY - at.getY();
        }
        Judgement judged = motion.critical(true, nearGround ? 0.1 : 1, rise, fall);
        if (judged.failed()) {
            return flag(attacker, track, CheckType.CRITICALS, 1, 1, judged.reason());
        }
        track.buffer(CheckType.CRITICALS, 1, 0.2).pass();
        return false;
    }

    private static String invalidAttack(Player attacker) {
        if (attacker.isDead()) {
            return "attacked while dead";
        }
        if (attacker.isSleeping()) {
            return "attacked while asleep";
        }
        if (attacker.isHandRaised() && attacker.getItemInUse() != null) {
            return "attacked while using " + attacker.getItemInUse().getType().name().toLowerCase(Locale.ROOT);
        }
        InventoryType open = attacker.getOpenInventory().getType();
        if (open != InventoryType.CRAFTING && open != InventoryType.CREATIVE) {
            return "attacked with a " + open.name().toLowerCase(Locale.ROOT) + " open";
        }
        return null;
    }

    /** Null if any line from the eye to the target is clear. */
    private static String throughWall(Player attacker, Entity target) {
        Location eye = attacker.getEyeLocation();
        BoundingBox box = target.getBoundingBox();
        Vector center = box.getCenter();
        List<Vector> points = List.of(center, new Vector(center.getX(), box.getMaxY() - 0.05, center.getZ()),
                new Vector(center.getX(), box.getMinY() + 0.1, center.getZ()),
                new Vector(box.getMinX() + 0.05, center.getY(), box.getMinZ() + 0.05),
                new Vector(box.getMaxX() - 0.05, center.getY(), box.getMaxZ() - 0.05));
        String blocker = null;
        for (Vector point : points) {
            Vector to = point.clone().subtract(eye.toVector());
            double distance = to.length();
            if (distance < 1e-3) {
                return null;
            }
            RayTraceResult hit = attacker.getWorld().rayTraceBlocks(eye, to.normalize(), distance,
                    FluidCollisionMode.NEVER, true);
            if (hit == null || hit.getHitBlock() == null) {
                return null;
            }
            blocker = hit.getHitBlock().getType().name().toLowerCase(Locale.ROOT);
        }
        return "hit " + target.getName() + " through " + blocker;
    }

    private static List<Vector> eyes(Player attacker, PlayerTrack track, long now) {
        List<Vector> eyes = new ArrayList<>();
        double eyeHeight = attacker.getEyeHeight();
        eyes.add(attacker.getEyeLocation().toVector());
        synchronized (track) {
            int taken = 0;
            var it = track.combat.history.descendingIterator();
            while (it.hasNext() && taken < 3) {
                double[] h = it.next();
                if (now - (long) h[0] > 250) {
                    break;
                }
                eyes.add(new Vector(h[1], h[2] + eyeHeight, h[3]));
                taken++;
            }
        }
        return eyes;
    }

    private List<BoundingBox> boxes(Entity target, long now, int ping) {
        BoundingBox current = target.getBoundingBox();
        if (target instanceof Player victim) {
            return new ArrayList<>(tracks.find(victim.getUniqueId())
                    .map(theirs -> MovementEngine.boxesWithin(theirs, now, ping + 200L, current))
                    .orElse(List.of(current)));
        }
        List<BoundingBox> boxes = new ArrayList<>();
        boxes.add(current);
        Vector velocity = target.getVelocity();
        boxes.add(current.clone().shift(velocity.clone().multiply(-Math.max(1, ping / 50.0 + 1))));
        return boxes;
    }

    private boolean flag(Player player, PlayerTrack track, CheckType check, double weight, double limit, String detail) {
        Buffer buffer = track.buffer(check, limit, 0.25);
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
        return "judging every melee attack: reach, walls, crosshair, what the attacker was doing";
    }
}
