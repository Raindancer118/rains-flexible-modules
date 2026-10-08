package de.raindancer.modules.anticheat.service;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.anticheat.AntiCheatSettings;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Anti-ESP: a player nobody could see — opaque blocks across every line between the two — is not
 * sent to the other's client, so a wallhack has nothing to draw. Five times a second, nearby pairs
 * only, a handful of rays each; looking slightly to the sides and ahead so somebody stepping round a
 * corner appears before they are in plain view.
 *
 * <p>Hiding a player through Bukkit also takes them off the tab list; {@link PacketTap} drops that
 * one packet for players hidden here, so tab lists stay as they are. Vanished players are never
 * touched — their own hiding belongs to RainsCore.
 */
public final class EspShield implements IAntiCheatService {

    private static final double ALWAYS_SEEN = 6;

    private final Plugin plugin;
    private final LogChannel log;
    private final Predicate<UUID> vanished;
    private final Map<UUID, Set<UUID>> hidden = new ConcurrentHashMap<>();
    private volatile AntiCheatSettings settings = AntiCheatSettings.DEFAULTS;
    private volatile PacketTap tap;
    private ScheduledTask task;

    public EspShield(Plugin plugin, LogChannel log, Predicate<UUID> vanished) {
        this.plugin = plugin;
        this.log = log;
        this.vanished = vanished;
    }

    public void tapWith(PacketTap tap) {
        this.tap = tap;
    }

    public void start() {
        if (Scheduling.isFolia()) {
            log.info("Anti-ESP is not available on Folia: it compares players across regions.");
            return;
        }
        task = Scheduling.globalTimer(plugin, 20, 4, ignored -> tick());
    }

    public void stop() {
        if (task != null) {
            task.cancel();
        }
        revealAll();
    }

    /** Whether the shield hid {@code target} from {@code viewer} — the tap asks before dropping a tab-list removal. */
    public boolean hides(UUID viewer, UUID target) {
        Set<UUID> theirs = hidden.get(viewer);
        return theirs != null && theirs.contains(target);
    }

    void tick() {
        AntiCheatSettings now = settings;
        if (!now.enabled() || !now.antiEsp()) {
            if (!hidden.isEmpty()) {
                revealAll();
            }
            return;
        }
        double range = now.antiEspRange();
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            World world = viewer.getWorld();
            Location eye = viewer.getEyeLocation();
            for (Player target : world.getPlayers()) {
                if (target == viewer) {
                    continue;
                }
                boolean isHidden = hides(viewer.getUniqueId(), target.getUniqueId());
                double distance = eye.distance(target.getLocation());
                if (vanished.test(target.getUniqueId())) {
                    if (isHidden) {
                        stopHiding(viewer, target, true);
                    }
                    continue;
                }
                if (distance > range) {
                    if (isHidden) {
                        stopHiding(viewer, target, false);
                    }
                    continue;
                }
                boolean seen = distance <= ALWAYS_SEEN || target.isGlowing() || viewer.getGameMode() == GameMode.SPECTATOR
                        || canSee(viewer, target);
                if (seen && isHidden) {
                    stopHiding(viewer, target, false);
                } else if (!seen && !isHidden && viewer.canSee(target)) {
                    hidden.computeIfAbsent(viewer.getUniqueId(), ignored -> ConcurrentHashMap.newKeySet()).add(target.getUniqueId());
                    viewer.hideEntity(plugin, target);
                }
            }
        }
    }

    /** Lets go first, so the packets that follow are not dropped. */
    private void stopHiding(Player viewer, Player target, boolean nowVanished) {
        Set<UUID> theirs = hidden.get(viewer.getUniqueId());
        if (theirs != null) {
            theirs.remove(target.getUniqueId());
        }
        viewer.showEntity(plugin, target);
        if (nowVanished && !viewer.canSee(target)) {
            // Vanish hid them while we already were, so nobody took them off this tab list.
            PacketTap through = tap;
            if (through != null) {
                through.unlist(viewer, target.getUniqueId());
            }
        }
    }

    /** Whether any line from around the viewer's eye reaches the target past nothing opaque. */
    static boolean canSee(Player viewer, Player target) {
        Location eye = viewer.getEyeLocation();
        Vector look = eye.getDirection().setY(0);
        Vector side = look.lengthSquared() < 1e-6 ? new Vector(1, 0, 0) : new Vector(-look.getZ(), 0, look.getX()).normalize().multiply(0.6);
        List<Vector> from = List.of(eye.toVector(), eye.toVector().add(side), eye.toVector().subtract(side),
                eye.toVector().add(viewer.getVelocity().clone().multiply(4)));
        BoundingBox box = target.getBoundingBox();
        Vector center = box.getCenter();
        List<Vector> to = new ArrayList<>(List.of(new Vector(center.getX(), box.getMaxY() - 0.1, center.getZ()), center,
                new Vector(center.getX(), box.getMinY() + 0.2, center.getZ()),
                new Vector(box.getMinX(), center.getY(), box.getMinZ()), new Vector(box.getMaxX(), center.getY(), box.getMaxZ())));
        World world = viewer.getWorld();
        for (Vector start : from) {
            for (Vector end : to) {
                Vector ray = end.clone().subtract(start);
                double length = ray.length();
                if (length < 1e-3) {
                    return true;
                }
                if (world.rayTraceBlocks(start.toLocation(world), ray.normalize(), length, FluidCollisionMode.NEVER, true,
                        block -> block.getType().isOccluding()) == null) {
                    return true;
                }
            }
        }
        return false;
    }

    public void forget(UUID player) {
        hidden.remove(player);
        hidden.values().forEach(set -> set.remove(player));
    }

    private void revealAll() {
        for (Map.Entry<UUID, Set<UUID>> entry : hidden.entrySet()) {
            Player viewer = plugin.getServer().getPlayer(entry.getKey());
            List<UUID> targets = new ArrayList<>(entry.getValue());
            entry.getValue().clear();
            if (viewer == null) {
                continue;
            }
            for (UUID id : targets) {
                Player target = plugin.getServer().getPlayer(id);
                if (target != null) {
                    viewer.showEntity(plugin, target);
                }
            }
        }
        hidden.clear();
    }

    @Override
    public void settings(AntiCheatSettings fresh) {
        this.settings = fresh == null ? AntiCheatSettings.DEFAULTS : fresh;
    }

    @Override
    public String describe() {
        return "hiding players behind walls from each other's clients";
    }
}
