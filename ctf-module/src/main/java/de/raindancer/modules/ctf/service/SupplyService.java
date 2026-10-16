package de.raindancer.modules.ctf.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.effect.Effect;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.effect.ParticleCue;
import de.raindancer.modules.ctf.CtfSettings;
import de.raindancer.modules.ctf.model.Point;
import de.raindancer.modules.ctf.model.Round;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * Supply drops: now and then a chest falls out of the sky onto one of the map's drop spots, a beam of light over it
 * until somebody takes it. Falling is only a display; the chest that lands is a power-up of its own kind.
 */
public final class SupplyService implements ICtfService {

    private static final int FALL_FROM = 30;
    private static final int FALL_TICKS = 60;

    private final Plugin plugin;
    private final Effects effects;
    private final PowerUpService powerUps;
    private final Random random = new Random();
    private volatile CtfSettings settings;
    private volatile long nextAt;
    private volatile Point waitingAt;

    public SupplyService(Plugin plugin, Effects effects, PowerUpService powerUps, CtfSettings settings) {
        this.plugin = plugin;
        this.effects = effects;
        this.powerUps = powerUps;
        this.settings = settings;
    }

    @Override
    public void settings(CtfSettings settings) {
        this.settings = settings;
    }

    public void start(long now) {
        nextAt = now + settings.supplySeconds() * 1000L;
        waitingAt = null;
    }

    /**
     * Once a second while a round runs: drops one when it is time, draws the beam over one waiting.
     *
     * @return where one started falling, for the announcer
     */
    public Optional<Point> tick(Round round, long now) {
        if (!settings.supplyDrops() || round.map().supplies().isEmpty()) {
            return Optional.empty();
        }
        Point waiting = waitingAt;
        if (waiting != null) {
            if (!powerUps.supplyWaiting()) {
                waitingAt = null;
                nextAt = now + settings.supplySeconds() * 1000L;
            } else {
                beam(round.world(), waiting);
            }
            return Optional.empty();
        }
        if (now < nextAt) {
            return Optional.empty();
        }
        List<Point> spots = round.map().supplies();
        Point at = round.abs(spots.get(random.nextInt(spots.size()))).plus(0.5, 0, 0.5);
        nextAt = Long.MAX_VALUE;
        fall(round.world(), at);
        return Optional.of(at);
    }

    private void fall(String worldName, Point at) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return;
        }
        Location top = new Location(world, at.x() - 0.5, at.y() + FALL_FROM, at.z() - 0.5);
        Scheduling.region(plugin, top, () -> {
            BlockDisplay chest = world.spawn(top, BlockDisplay.class, display -> {
                display.setBlock(Material.CHEST.createBlockData());
                display.setPersistent(false);
                display.setGlowing(true);
            });
            // A tick later, so the client has the display before it is told to move it: it then slides down. Region,
            // not the display's own scheduler — a display is not ticked, so its scheduler never runs.
            Scheduling.regionLater(plugin, top, 2L, () -> {
                chest.setInterpolationDelay(0);
                chest.setInterpolationDuration(FALL_TICKS);
                chest.setTransformation(new Transformation(new Vector3f(0, -FALL_FROM, 0),
                        chest.getTransformation().getLeftRotation(), chest.getTransformation().getScale(),
                        chest.getTransformation().getRightRotation()));
            });
            Scheduling.regionLater(plugin, top, FALL_TICKS + 4L, () -> {
                chest.remove();
                waitingAt = at;
                powerUps.landSupply(worldName, at);
                effects.playAt(worldName, at.x(), at.y(), at.z(), Effect.of(
                        new ParticleCue("EXPLOSION", 3, 0.5, 0.2, 0.5, 0.0, null)));
            });
        });
    }

    private void beam(String world, Point at) {
        for (int up = 1; up < 24; up += 2) {
            effects.playAt(world, at.x(), at.y() + up, at.z(), Effect.of(
                    new ParticleCue("END_ROD", 1, 0.05, 0.4, 0.05, 0.0, null)));
        }
    }

    public void stop() {
        waitingAt = null;
        nextAt = Long.MAX_VALUE;
    }
}
