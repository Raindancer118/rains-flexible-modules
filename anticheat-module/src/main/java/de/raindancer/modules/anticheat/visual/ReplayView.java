package de.raindancer.modules.anticheat.visual;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.world.visual.PathTrail;
import de.raindancer.modules.anticheat.model.ReplayFrame;
import de.raindancer.modules.anticheat.model.Replays;
import de.raindancer.modules.anticheat.rules.Geometry;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * A frozen replay drawn in the air for one staff member: the path from green (oldest) to red (the
 * moment of the alert), and where the player was looking every few frames, as a short white line.
 */
public final class ReplayView {

    private static final int SECONDS = 20;

    private ReplayView() {
    }

    /** @return where the replay is, so the caller can say so; null if it has no frames */
    public static Location show(Plugin plugin, Player viewer, Replays.Replay replay) {
        List<ReplayFrame> frames = replay.frames();
        if (frames.isEmpty()) {
            return null;
        }
        World world = Bukkit.getWorld(frames.getFirst().world());
        if (world == null) {
            return null;
        }
        List<List<PathTrail.Dot>> segments = new ArrayList<>();
        List<Particle.DustOptions> colours = new ArrayList<>();
        List<PathTrail.Dot> looks = new ArrayList<>();
        for (int i = 1; i < frames.size(); i++) {
            ReplayFrame from = frames.get(i - 1);
            ReplayFrame to = frames.get(i);
            segments.add(PathTrail.toward(from.x(), from.y() + 0.1, from.z(), to.x(), to.y() + 0.1, to.z(), 0, 0.15, 10));
            double along = (double) i / frames.size();
            colours.add(new Particle.DustOptions(Color.fromRGB((int) (255 * along), (int) (255 * (1 - along)), 40),
                    to.onGround() ? 0.7f : 1.1f));
            if (i % 4 == 0) {
                Vector look = Geometry.direction(to.yaw(), to.pitch());
                looks.addAll(PathTrail.toward(to.x(), to.y() + 1.62, to.z(), to.x() + look.getX(), to.y() + 1.62 + look.getY(),
                        to.z() + look.getZ(), 0.2, 0.2, 1));
            }
        }
        Particle.DustOptions lookColour = new Particle.DustOptions(Color.WHITE, 0.5f);
        long[] left = {SECONDS * 2L};
        Scheduling.entityTimer(plugin, viewer, 1, 10, task -> {
            if (!viewer.isOnline() || !viewer.getWorld().equals(world) || left[0]-- <= 0) {
                task.cancel();
                return;
            }
            for (int i = 0; i < segments.size(); i++) {
                PathTrail.draw(viewer, world, segments.get(i), colours.get(i));
            }
            PathTrail.draw(viewer, world, looks, lookColour);
        });
        ReplayFrame start = frames.getFirst();
        return new Location(world, start.x(), start.y(), start.z());
    }
}
