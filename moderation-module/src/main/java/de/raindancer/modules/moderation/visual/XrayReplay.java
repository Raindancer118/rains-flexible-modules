package de.raindancer.modules.moderation.visual;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.world.visual.PathTrail;
import de.raindancer.modules.moderation.model.MiningLedger;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * A suspect's recent tunnels, drawn in the air for one moderator only: grey for rock they dug, aqua
 * for every new ore vein their digging revealed, red for every bait ore they reached.
 */
public final class XrayReplay {

    private static final int SECONDS = 30;
    private static final double RANGE = 64;
    private static final Particle.DustOptions ROCK = new Particle.DustOptions(Color.fromRGB(0x9E9E9E), 0.6f);
    private static final Particle.DustOptions ORE = new Particle.DustOptions(Color.fromRGB(0x29E6F2), 1.6f);
    private static final Particle.DustOptions BAIT = new Particle.DustOptions(Color.fromRGB(0xFF2A2A), 2.0f);

    private XrayReplay() {
    }

    /** @return how many dug blocks are in range to be drawn */
    public static int show(Plugin plugin, Player viewer, MiningLedger ledger) {
        World world = viewer.getWorld();
        if (!world.getName().equals(ledger.trailWorld())) {
            return 0;
        }
        Location eye = viewer.getLocation();
        List<PathTrail.Dot> rock = new ArrayList<>();
        List<PathTrail.Dot> ore = new ArrayList<>();
        List<PathTrail.Dot> bait = new ArrayList<>();
        for (int[] step : ledger.trail()) {
            double x = step[0] + 0.5;
            double y = step[1] + 0.5;
            double z = step[2] + 0.5;
            if (eye.distanceSquared(new Location(world, x, y, z)) > RANGE * RANGE) {
                continue;
            }
            PathTrail.Dot dot = new PathTrail.Dot(x, y, z);
            (step[3] == -2 ? bait : step[3] >= 0 ? ore : rock).add(dot);
        }
        int shown = rock.size() + ore.size() + bait.size();
        if (shown == 0) {
            return 0;
        }
        long[] left = {SECONDS * 2L};
        Scheduling.entityTimer(plugin, viewer, 1, 10, task -> {
            if (!viewer.isOnline() || !viewer.getWorld().equals(world) || left[0]-- <= 0) {
                task.cancel();
                return;
            }
            PathTrail.draw(viewer, world, rock, ROCK);
            PathTrail.draw(viewer, world, ore, ORE);
            PathTrail.draw(viewer, world, bait, BAIT);
        });
        return shown;
    }

    /**
     * Their tunnels, wherever the viewer is: drawn at once when they stand within range of the digging, else after
     * taking them to the last block dug — open tunnel, so nothing to suffocate in. What a report's one button does.
     *
     * @param mayTeleport whether the viewer may be taken there; without it the trail is only drawn where they stand
     * @param told how many dug blocks were drawn, once that is known (0: nothing of theirs in that world)
     */
    public static void goAndShow(Plugin plugin, Player viewer, MiningLedger ledger, boolean mayTeleport,
                                 java.util.function.IntConsumer told) {
        if (!mayTeleport) {
            told.accept(show(plugin, viewer, ledger));
            return;
        }
        Location at = viewer.getLocation();
        java.util.Optional<int[]> spot = whereToStand(ledger.trail(), ledger.trailWorld(), viewer.getWorld().getName(),
                at.getX(), at.getY(), at.getZ());
        World world = org.bukkit.Bukkit.getWorld(ledger.trailWorld());
        if (spot.isEmpty() || world == null) {
            told.accept(show(plugin, viewer, ledger));
            return;
        }
        int[] step = spot.get();
        Location stand = new Location(world, step[0] + 0.5, step[1], step[2] + 0.5, at.getYaw(), at.getPitch());
        viewer.teleportAsync(stand).thenRun(() -> Scheduling.entity(plugin, viewer,
                () -> told.accept(show(plugin, viewer, ledger))));
    }

    /** Where to stand to see the trail: empty when already within range of it (or there is none), else its last step. */
    static java.util.Optional<int[]> whereToStand(List<int[]> trail, String trailWorld, String viewerWorld,
                                                  double x, double y, double z) {
        if (trail.isEmpty()) {
            return java.util.Optional.empty();
        }
        if (trailWorld.equals(viewerWorld)) {
            for (int[] step : trail) {
                double dx = step[0] + 0.5 - x;
                double dy = step[1] + 0.5 - y;
                double dz = step[2] + 0.5 - z;
                if (dx * dx + dy * dy + dz * dz <= RANGE * RANGE) {
                    return java.util.Optional.empty();
                }
            }
        }
        int[] last = trail.getLast();
        return java.util.Optional.of(new int[]{last[0], last[1], last[2]});
    }
}
