package de.raindancer.modules.manhunt.service;

import de.raindancer.modules.manhunt.model.ChaosAction;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Throws a {@link ChaosAction} at whatever run is currently going — both a console and
 * {@code ManhuntChaosMenu} go through this, so the cooldown and "is anybody even there to act on"
 * checks live in exactly one place.
 *
 * <h2>Why every action is cosmetic or reversible</h2>
 * Nothing here ends a life on its own — {@link ChaosAction#LIGHTNING_ON_A_RUNNER} is
 * {@code strikeLightningEffect}, which draws the bolt and the sound without the damage or the fire a
 * real strike carries, and every potion effect is short and named for what it is when a player reads
 * their own effect list. A host "throwing chaos" at a hunt is meant to shake up who is ahead, not to
 * hand one side a kill nobody can see coming from a menu click.
 */
public final class ChaosService {

    /** What {@link #apply} answered. */
    public enum Result { APPLIED, ON_COOLDOWN, NO_TARGETS, NOT_RUNNING }

    private final Plugin plugin;
    private final ManhuntService manhunt;
    private final Clock clock;
    private final Random random;

    private volatile Instant lastFired = Instant.EPOCH;

    public ChaosService(Plugin plugin, ManhuntService manhunt) {
        this(plugin, manhunt, Clock.systemUTC(), new Random());
    }

    /** For tests: an injectable clock and a seeded {@link Random} for deterministic shuffling. */
    ChaosService(Plugin plugin, ManhuntService manhunt, Clock clock, Random random) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.manhunt = Objects.requireNonNull(manhunt, "manhunt");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    public Result apply(ChaosAction action) {
        Objects.requireNonNull(action, "action");
        if (!manhunt.isRunning()) {
            return Result.NOT_RUNNING;
        }
        Instant now = Instant.now(clock);
        int cooldown = manhunt.config().chaosCooldownSecondsClamped();
        if (!cooldownElapsed(lastFired, now, cooldown)) {
            return Result.ON_COOLDOWN;
        }
        boolean applied = switch (action) {
            case SWAP_POSITIONS -> swapPositions();
            case REVEAL_RUNNERS -> revealRunners();
            case HASTE_HUNTERS -> hasteHunters();
            case SLOW_RUNNERS -> slowRunners();
            case LIGHTNING_ON_A_RUNNER -> strikeNearRandomRunner();
            case FLIP_WEATHER -> flipWeather();
        };
        if (!applied) {
            return Result.NO_TARGETS;
        }
        lastFired = now;
        return Result.APPLIED;
    }

    /** Pulled out as a pure function so the gate itself is testable without a clock actually ticking. */
    static boolean cooldownElapsed(Instant last, Instant now, int cooldownSeconds) {
        if (cooldownSeconds <= 0) {
            return true;
        }
        return !Duration.between(last, now).minusSeconds(cooldownSeconds).isNegative();
    }

    /** Seconds left before {@link #apply} would answer anything but {@link Result#ON_COOLDOWN} — for
     *  the menu to grey the button with a number rather than just refusing the click. */
    public int secondsUntilReady() {
        int cooldown = manhunt.config().chaosCooldownSecondsClamped();
        if (cooldown <= 0) {
            return 0;
        }
        long elapsed = Duration.between(lastFired, Instant.now(clock)).getSeconds();
        return (int) Math.max(0, cooldown - elapsed);
    }

    // ------------------------------------------------------------------------ the actions themselves

    private boolean swapPositions() {
        List<Player> alive = livingOf(manhunt.teams().everybody());
        if (alive.size() < 2) {
            return false;
        }
        List<Location> spots = new ArrayList<>(alive.size());
        for (Player player : alive) {
            spots.add(player.getLocation());
        }
        Collections.shuffle(spots, random);
        for (int i = 0; i < alive.size(); i++) {
            alive.get(i).teleportAsync(spots.get(i));
        }
        return true;
    }

    private boolean revealRunners() {
        return applyEffect(manhunt.teams().runners(), PotionEffectType.GLOWING, 15);
    }

    private boolean hasteHunters() {
        return applyEffect(manhunt.teams().hunters(), PotionEffectType.SPEED, 20);
    }

    private boolean slowRunners() {
        return applyEffect(manhunt.teams().runners(), PotionEffectType.SLOWNESS, 15);
    }

    private boolean applyEffect(Set<UUID> targets, PotionEffectType type, int seconds) {
        List<Player> alive = livingOf(targets);
        if (alive.isEmpty()) {
            return false;
        }
        for (Player player : alive) {
            player.addPotionEffect(new PotionEffect(type, seconds * 20, 0, false, true, true));
        }
        return true;
    }

    private boolean strikeNearRandomRunner() {
        List<Player> runners = livingOf(manhunt.teams().runners());
        if (runners.isEmpty()) {
            return false;
        }
        Player target = runners.get(random.nextInt(runners.size()));
        World world = target.getWorld();
        if (world != null) {
            world.strikeLightningEffect(target.getLocation());
        }
        return true;
    }

    private boolean flipWeather() {
        World world = plugin.getServer().getWorld(manhunt.config().worldName());
        if (world == null) {
            return false;
        }
        world.setStorm(!world.hasStorm());
        return true;
    }

    private List<Player> livingOf(Set<UUID> ids) {
        List<Player> alive = new ArrayList<>();
        for (UUID id : ids) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null && player.isOnline() && !player.isDead()) {
                alive.add(player);
            }
        }
        return alive;
    }
}
