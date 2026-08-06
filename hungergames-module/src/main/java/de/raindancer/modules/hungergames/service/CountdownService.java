package de.raindancer.modules.hungergames.service;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.bossbar.BarPriority;
import de.raindancer.core.ui.bossbar.BarStyle;
import de.raindancer.core.ui.bossbar.BossBars;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.model.ArenaLayout;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.store.GameSession;
import de.raindancer.modules.hungergames.visual.BarrierRing;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@code /start}: the countdown, and the moment the barrier rings come off.
 *
 * <h2>Why the flag, when everything else in this module asks the phase</h2>
 * Because the phase does not change when the countdown starts. {@code /init} moves to LOBBY and
 * {@code /startup} moves to READY, so a second attempt at either is refused by the transition itself. The
 * countdown leaves the round in READY for its whole length and only moves to RUNNING at zero — so without a
 * flag of its own, a second {@code /start}, a second click of the button, or an API call from a dashboard
 * would start a second countdown with a second boss bar, and the games would begin twice.
 *
 * <p>{@link #isRunning()} also re-checks the phase, which is what clears the flag implicitly: a round reset
 * mid-countdown leaves the flag set, and a flag that outlives its round is one that blocks the next one from
 * ever starting.
 *
 * <h2>What happens at zero, and the order it happens in</h2>
 * Fireworks, then the rings come down, then the effects are stripped and everybody is put into survival, then
 * the round is announced and the phase moves to RUNNING. The rings before the effects, deliberately: a
 * tribute in survival mode with no jump boost and a barrier still at head height is a tribute standing in a
 * box, and the gap is visible even at one tick.
 *
 * <h2>What was left behind</h2>
 * The source de-opped admins here, by hand, and never gave the operator status back — a gamemaster who ran a
 * round was locked out of their own server afterwards. That is {@code OpTrackerService}'s job now, keyed off
 * the phase change this method causes, and it restores what it took.
 */
public final class CountdownService implements IHungerGamesService {

    /** How often the countdown ticks. Once a second, which is what it is counting in. */
    private static final long SECOND_IN_TICKS = 20L;

    /** Below this many seconds left, a sound plays every second. */
    public static final int AUDIBLE_FROM = 10;

    /** Below this many seconds left, the number is shown as a title across the screen. */
    public static final int TITLE_FROM = 5;

    /** How many fireworks go up around the middle, and how far out. */
    private static final int FIREWORKS = 8;
    private static final double FIREWORK_RADIUS = 5.0;

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** Whoever is connected right now. */
    @FunctionalInterface
    public interface OnlinePlayers {
        List<Player> all();
    }

    /** Told how it went, for the round log and whoever pressed the button. */
    public interface Told {

        void counting(UUID who, int seconds);

        void begun(UUID who);

        void refused(UUID who, String why);
    }

    private final Plugin plugin;
    private final GameSession session;
    private final ArenaBuildService arena;
    private final OnlinePlayers online;
    private final BossBars bossBars;
    private final Effects effects;
    private final Messages messages;
    private final Told told;
    private final LogChannel log;

    private volatile HungerGamesSettings settings = HungerGamesSettings.DEFAULTS;
    private final AtomicBoolean counting = new AtomicBoolean();

    public CountdownService(Plugin plugin, GameSession session, ArenaBuildService arena,
                            OnlinePlayers online, BossBars bossBars, Effects effects, Messages messages,
                            Told told, LogChannel log) {
        this.plugin = plugin;
        this.session = session;
        this.arena = arena;
        this.online = online;
        this.bossBars = bossBars;
        this.effects = effects;
        this.messages = messages;
        this.told = told;
        this.log = log;
    }

    @Override
    public void settings(HungerGamesSettings settings) {
        this.settings = settings;
    }

    /**
     * Whether a countdown is ticking right now.
     *
     * <p>The phase check is what makes a stale flag harmless: a round reset mid-countdown is no longer READY,
     * so this answers false and the next {@code /start} is allowed through.
     */
    public boolean isRunning() {
        return counting.get() && session.phase() == GamePhase.READY;
    }

    /** The {@code /start} stage, in the shape {@link GameControlService.Stage} takes. */
    public GameControlService.Stage startStage() {
        return this::run;
    }

    /** What {@code GameControlService} asks to know whether a second start should be refused. */
    public boolean isCountdownActiveFor(UUID ignored) {
        return isRunning();
    }

    /** Runs the countdown. Returns whether it started. */
    public boolean run(UUID actor) {
        if (session.phase() != GamePhase.READY) {
            told.refused(actor, "starting only works from READY (currently " + session.phase() + ")");
            return false;
        }
        if (!counting.compareAndSet(false, true)) {
            told.refused(actor, "the countdown is already running — the games begin shortly");
            log.warn("A second start was ignored: the countdown is already running.");
            return false;
        }

        // Everything except slowness. Slowness survives because the version this replaces used it to hold
        // tributes still and a round could still be mid-migration to the barrier rings; everything else is
        // whatever they picked up in the lobby, and starting a fight to the death with somebody's leftover
        // strength potion still ticking is not a countdown, it is a head start.
        for (Player tribute : tributesOnline()) {
            for (PotionEffect effect : tribute.getActivePotionEffects()) {
                if (effect.getType() != PotionEffectType.SLOWNESS) {
                    tribute.removePotionEffect(effect.getType());
                }
            }
        }

        int seconds = settings.countdown();
        told.counting(actor, seconds);
        log.info("The countdown has started: {} seconds.", seconds);
        tick(actor, seconds, seconds);
        return true;
    }

    /**
     * One second of the countdown, and the next.
     *
     * <p>Scheduled a second at a time rather than as a repeating task so that the cancellation condition —
     * the round no longer being READY — is checked before every single tick rather than by a task that has
     * to remember to cancel itself.
     */
    private void tick(UUID actor, int remaining, int total) {
        if (session.phase() != GamePhase.READY) {
            clearTheBar();
            counting.set(false);
            log.warn("The countdown was abandoned: the round is no longer READY (it is {}).",
                    session.phase());
            return;
        }
        if (remaining <= 0) {
            clearTheBar();
            begin(actor);
            return;
        }

        showTheBar(remaining, total);
        if (remaining <= AUDIBLE_FROM) {
            for (Player watcher : online.all()) {
                effects.play(watcher.getUniqueId(), HungerGamesCues.COUNTDOWN);
            }
        }
        if (remaining <= TITLE_FROM) {
            showTheNumber(remaining);
        }

        Scheduling.globalLater(plugin, SECOND_IN_TICKS, () -> tick(actor, remaining - 1, total));
    }

    /**
     * The boss bar, shown to everybody online rather than only to tributes.
     *
     * <p>Spectators, staff and whoever is streaming are all watching the same clock. A bar only tributes can
     * see is a bar the commentary cannot refer to.
     */
    private void showTheBar(int remaining, int total) {
        Component text = messages.get("hungergames.countdown-bar",
                "seconds", String.valueOf(remaining));
        BossBar.Color colour = remaining <= 3 ? BossBar.Color.GREEN
                : remaining <= TITLE_FROM ? BossBar.Color.YELLOW
                : BossBar.Color.RED;

        bossBars.showShared("hungergames", "countdown",
                online.all().stream().map(Player::getUniqueId).toList(),
                BarStyle.of(text).progress((float) remaining / Math.max(1, total)).colour(colour),
                BarPriority.HIGH);
    }

    private void clearTheBar() {
        bossBars.clearShared("hungergames", "countdown");
    }

    /** The last five seconds, big, in the middle of the screen. */
    private void showTheNumber(int remaining) {
        String colour = remaining <= 1 ? "green" : remaining <= 3 ? "yellow" : "red";
        Component title = MINI.deserialize("<bold><" + colour + ">" + remaining);
        Component subtitle = messages.get("hungergames.countdown-subtitle");

        for (Player watcher : online.all()) {
            watcher.showTitle(net.kyori.adventure.title.Title.title(title, subtitle,
                    net.kyori.adventure.title.Title.Times.times(Duration.ZERO,
                            Duration.ofMillis(1_250), Duration.ofMillis(250))));
        }
    }

    // ==================== zero ====================

    /**
     * The games begin.
     *
     * <p>Reached at the bottom of {@link #tick}'s own {@code Scheduling.globalLater} chain, so this itself
     * always runs on the global region thread — which is exactly right for {@code world.setDifficulty} and
     * {@code world.setTime}, world-wide state with no single region to own it. It is <em>not</em> right for
     * the barrier rings, each a block at a specific platform, owned by whichever region contains it rather
     * than by the global thread. Those go through {@link #ringsDown}, one region task for every platform —
     * an arena's platforms all sit inside the single region {@code ArenaBuildService} built the whole arena
     * in (see that class's own note on why one {@code Scheduling.region} call covers the entire build) — and
     * everything that has to happen only once the rings are actually down runs from inside that same
     * callback, so "rings before effects" still holds exactly rather than racing a region hop that has not
     * finished yet.
     */
    private void begin(UUID actor) {
        counting.set(false);
        Optional<ArenaLayout> maybe = arena.layout();
        Optional<World> maybeWorld = maybe.map(layout -> plugin.getServer().getWorld(layout.world()));

        if (maybe.isEmpty() || maybeWorld.isEmpty()) {
            log.warn("The countdown finished but no arena is loaded, so no rings were removed.");
            finishBeginning(actor);
            return;
        }

        World world = maybeWorld.get();
        ArenaLayout layout = maybe.get();

        fireworks(world, layout);
        world.setDifficulty(settings.gameDifficulty());
        // The same hour every round — the launch sequence set it, and the countdown may have taken a
        // minute since.
        world.setTime(StartupSequenceService.SUNSET);

        ringsDown(world, layout, actor);
    }

    /** Takes every platform's barrier ring down, on the region that owns them, then finishes the round start. */
    private void ringsDown(World world, ArenaLayout layout, UUID actor) {
        Location centre = new Location(world, layout.centreX(), layout.centreY(), layout.centreZ());
        Scheduling.region(plugin, centre, () -> {
            // Before the effects come off, so nobody is briefly free to move inside a box.
            for (ArenaLayout.Stand platform : layout.platforms()) {
                BarrierRing.remove(world, platform.blockX(), platform.blockY(), platform.blockZ());
            }
            log.info("The barrier rings are down around all {} platform(s).", layout.platformCount());
            finishBeginning(actor);
        });
    }

    /** Everybody into survival, the round announced, and the phase moved to RUNNING. */
    private void finishBeginning(UUID actor) {
        for (Player tribute : tributesOnline()) {
            tribute.removePotionEffect(PotionEffectType.SLOWNESS);
            tribute.removePotionEffect(PotionEffectType.JUMP_BOOST);
            // Survival, at last: until this moment they were in adventure and could not break the block
            // they were standing on, let alone open a chest.
            tribute.setGameMode(GameMode.SURVIVAL);
        }

        for (Player watcher : online.all()) {
            messages.send(watcher, "hungergames.game-start");
            effects.play(watcher.getUniqueId(), HungerGamesCues.GAME_START);
        }

        if (!session.transitionTo(GamePhase.RUNNING)) {
            log.error("The countdown finished but the round would not move into RUNNING (it is {}).",
                    session.phase());
            told.refused(actor, "the round would not move into RUNNING from " + session.phase());
            return;
        }
        log.info("The round is running.");
        told.begun(actor);
    }

    /**
     * Eight fireworks in a ring above the middle, staggered, and one white burst over the centre.
     *
     * <p>Spawned as entities rather than played as a cue: a firework is the one effect here that has to be
     * a real thing in the world, because it is what a tribute standing on a platform looks up at and what a
     * camera pointed at the cornucopia sees.
     *
     * <p>{@code Scheduling.globalLater} only supplies the stagger — the delay between one firework and the
     * next — and {@link #begin} is itself already running on the global thread when this is called, so that
     * much needs no further hop. Spawning the entity is a different matter: it is a mutation at one specific
     * location, owned by whichever region contains it, not by the global thread the delay lands on. Each
     * delayed callback therefore re-hops onto {@code Scheduling.region} for that one location before calling
     * {@link #spawnOne} — the delay and the region hop are two separate concerns, each using the scheduler
     * built for it.
     */
    private void fireworks(World world, ArenaLayout layout) {
        Color[] colours = {Color.ORANGE, Color.RED, Color.YELLOW, Color.MAROON};

        for (int i = 0; i < FIREWORKS; i++) {
            double angle = (2 * Math.PI * i) / FIREWORKS;
            Location where = new Location(world,
                    layout.centreX() + 0.5 + FIREWORK_RADIUS * Math.cos(angle),
                    layout.centreY() + 5,
                    layout.centreZ() + 0.5 + FIREWORK_RADIUS * Math.sin(angle));
            Color colour = colours[i % colours.length];
            Scheduling.globalLater(plugin, 1L + i * 3L,
                    () -> Scheduling.region(plugin, where, () -> spawnOne(where, colour)));
        }
        Location whiteBurst = new Location(world, layout.centreX() + 0.5, layout.centreY() + 8,
                layout.centreZ() + 0.5);
        Scheduling.globalLater(plugin, 25L,
                () -> Scheduling.region(plugin, whiteBurst, () -> spawnOne(whiteBurst, Color.WHITE)));
    }

    private void spawnOne(Location where, Color colour) {
        World world = where.getWorld();
        if (world == null) {
            return;
        }
        Firework firework = world.spawn(where, Firework.class);
        FireworkMeta meta = firework.getFireworkMeta();
        meta.addEffect(FireworkEffect.builder()
                .flicker(true)
                .trail(true)
                .with(FireworkEffect.Type.BALL_LARGE)
                .withColor(colour)
                .withFade(Color.WHITE)
                .build());
        meta.setPower(1);
        firework.setFireworkMeta(meta);
    }

    private List<Player> tributesOnline() {
        return online.all().stream()
                .filter(player -> session.isWhitelisted(player.getUniqueId()))
                .toList();
    }

    @Override
    public String describe() {
        return "the countdown, and the moment the rings come off";
    }
}
