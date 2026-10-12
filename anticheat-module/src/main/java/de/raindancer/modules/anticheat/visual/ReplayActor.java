package de.raindancer.modules.anticheat.visual;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.anticheat.model.ReplayFrame;
import de.raindancer.modules.anticheat.model.ReplayTimeline;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import de.raindancer.modules.anticheat.model.ReplayLoadout;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Pose;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A replay acted out: a mannequin in the player's skin, armour and off hand walks the recorded path at the
 * pace it happened, holding what they held and crouching or gliding when they did, for
 * one staff member's eyes only, three times over, with the check and the time in the action bar — red at
 * the moment the check alerted. Nothing about it is saved; it is gone when it ends, when the staff member
 * leaves, or on {@code /anticheat replay stop}.
 */
public final class ReplayActor {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final int PLAYS = 3;
    /** A breath between plays, so the end and the next start are not one jump. */
    private static final long PAUSE_MILLIS = 1000;
    /** Whoever watches what, so a second replay replaces the first rather than standing beside it. */
    private static final Map<UUID, Mannequin> watching = new ConcurrentHashMap<>();
    /** Which plugin each figure's tasks run under, so stopping one schedules on its own thread. */
    private static final Map<Mannequin, Plugin> owners = new ConcurrentHashMap<>();
    /** What to do once a figure is gone, however it went: put the watcher back. */
    private static final Map<Mannequin, Runnable> afterwards = new ConcurrentHashMap<>();

    private ReplayActor() {
    }

    /**
     * @param subject whose skin the figure wears
     * @param check   what alerted, for the action bar
     */
    public static void play(Plugin plugin, Player viewer, UUID subject, String subjectName, String check,
                            java.util.List<ReplayFrame> frames, ReplayLoadout loadout, Runnable ended) {
        if (frames.isEmpty()) {
            return;
        }
        replaced(viewer);
        ReplayTimeline timeline = new ReplayTimeline(frames);
        ReplayFrame first = frames.getFirst();
        World world = Bukkit.getWorld(first.world());
        if (world == null) {
            return;
        }
        Location start = new Location(world, first.x(), first.y(), first.z(), first.yaw(), first.pitch());
        Scheduling.region(plugin, start, () -> {
            Mannequin figure = world.spawn(start, Mannequin.class, spawned -> {
                spawned.setPersistent(false);
                spawned.setVisibleByDefault(false);
                spawned.setInvulnerable(true);
                spawned.setSilent(true);
                spawned.setGravity(false);
                spawned.setAI(false);
                spawned.setCollidable(false);
                spawned.setImmovable(true);
                spawned.setProfile(ResolvableProfile.resolvableProfile(Bukkit.createProfile(subject, subjectName)));
                spawned.customName(MINI.deserialize("<white>" + subjectName + " <dark_gray>(replay)"));
                spawned.setCustomNameVisible(true);
                spawned.setDescription(MINI.deserialize("<yellow>" + check));
                dress(spawned, loadout);
            });
            watching.put(viewer.getUniqueId(), figure);
            owners.put(figure, plugin);
            if (ended != null) {
                afterwards.put(figure, ended);
            }
            Scheduling.entity(plugin, viewer, () -> viewer.showEntity(plugin, figure));
            long length = timeline.millis();
            long[] elapsed = {0};
            Scheduling.entityTimer(plugin, figure, 1, 1, task -> {
                long round = length + PAUSE_MILLIS;
                if (!viewer.isOnline() || watching.get(viewer.getUniqueId()) != figure || elapsed[0] >= round * PLAYS) {
                    task.cancel();
                    watching.remove(viewer.getUniqueId(), figure);
                    owners.remove(figure);
                    figure.remove();
                    done(figure);
                    return;
                }
                long into = Math.min(length, elapsed[0] % round);
                ReplayFrame now = timeline.at(into);
                figure.teleportAsync(new Location(world, now.x(), now.y(), now.z(), now.yaw(), now.pitch()));
                figure.setRotation(now.yaw(), now.pitch());
                stance(figure, now, loadout);
                boolean alert = timeline.isAlert(into);
                viewer.sendActionBar(MINI.deserialize((alert ? "<red>⚠ " : "<gray>") + "Replay · <yellow>" + check
                        + (alert ? " <red>alerts here" : "") + " <dark_gray>· <white>"
                        + String.format(Locale.ROOT, "%.1f / %.1f s", into / 1000.0, length / 1000.0)
                        + " <dark_gray>· play " + (elapsed[0] / round + 1) + "/" + PLAYS));
                elapsed[0] += 50;
            }, () -> watching.remove(viewer.getUniqueId(), figure));
        });
    }

    /** Ends whatever this staff member is watching. @return whether anything was playing */
    public static boolean stop(Player viewer) {
        Mannequin figure = watching.remove(viewer.getUniqueId());
        if (figure == null) {
            return false;
        }
        Plugin owner = owners.remove(figure);
        if (owner != null) {
            Scheduling.entity(owner, figure, figure::remove);
        }
        done(figure);
        return true;
    }

    /** A new replay takes the old one's place: the old figure goes, without putting the watcher back yet. */
    private static void replaced(Player viewer) {
        Mannequin figure = watching.remove(viewer.getUniqueId());
        if (figure == null) {
            return;
        }
        afterwards.remove(figure);
        Plugin owner = owners.remove(figure);
        if (owner != null) {
            Scheduling.entity(owner, figure, figure::remove);
        }
    }

    /** On disable: nothing outlives the plugin, and every watcher is put back. */
    public static void stopAll() {
        watching.values().forEach(figure -> {
            if (figure.isValid()) {
                figure.remove();
            }
            done(figure);
        });
        watching.clear();
        owners.clear();
    }

    private static void done(Mannequin figure) {
        Runnable then = afterwards.remove(figure);
        if (then != null) {
            then.run();
        }
    }

    /** For a status line: whether this staff member is watching one. */
    public static boolean isWatching(Player viewer) {
        return watching.containsKey(viewer.getUniqueId());
    }

    /** Their armour and off hand, as they wore them at the alert. */
    private static void dress(Mannequin figure, ReplayLoadout loadout) {
        if (loadout == null) {
            return;
        }
        EntityEquipment gear = figure.getEquipment();
        gear.setHelmet(Loadouts.item(loadout.armour().get(0)));
        gear.setChestplate(Loadouts.item(loadout.armour().get(1)));
        gear.setLeggings(Loadouts.item(loadout.armour().get(2)));
        gear.setBoots(Loadouts.item(loadout.armour().get(3)));
        // No drop chances: a mannequin is not a mob, has none, and throws if asked to set one.
        gear.setItemInOffHand(Loadouts.item(loadout.offHand()));
    }

    /** What was in their hand at this moment, and whether they crouched or glided. */
    private static void stance(Mannequin figure, ReplayFrame now, ReplayLoadout loadout) {
        if (loadout != null && now.slot() >= 0 && now.slot() < 9) {
            figure.getEquipment().setItemInMainHand(Loadouts.item(loadout.hotbar().get(now.slot())));
        }
        Pose pose = now.gliding() ? Pose.FALL_FLYING : now.sneaking() ? Pose.SNEAKING : Pose.STANDING;
        if (Mannequin.validPoses().contains(pose) && figure.getPose() != pose) {
            figure.setPose(pose, true);
        }
    }
}
