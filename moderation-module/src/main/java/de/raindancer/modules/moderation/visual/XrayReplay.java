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
}
