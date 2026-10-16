package de.raindancer.modules.ctf.service;

import de.raindancer.core.content.achievement.Achievement;
import de.raindancer.core.content.achievement.Achievements;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.social.economy.Economies;
import de.raindancer.core.social.economy.Economy;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.social.economy.PrizePot;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.ctf.CtfSettings;
import de.raindancer.modules.ctf.model.CaptureShow;
import de.raindancer.modules.ctf.model.Challenge;
import de.raindancer.modules.ctf.model.Role;
import de.raindancer.modules.ctf.model.Stats;
import de.raindancer.modules.ctf.model.Trail;
import de.raindancer.modules.ctf.rules.ChallengeRule;
import de.raindancer.modules.ctf.rules.LevelRule;
import de.raindancer.modules.ctf.store.ProfileStore;
import de.raindancer.modules.ctf.store.StatsStore;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What playing earns: experience and the levels it makes (and the trails and fireworks they unlock), the day's
 * challenges, achievements, money — and the records. Everything that touches the database runs off the server thread;
 * what a player sees of it is cached here, read when they come into the lobby.
 */
public final class ProgressService implements ICtfService {

    /** What happened, for the challenges. */
    public enum Deed { CAPTURE, RETURN, KILL, CARRIER_KILL, WIN, POWER_UP, ESCORT, PLAYED }

    /** Achievement ids. */
    public static final String FIRST_CAPTURE = "first-capture";
    public static final String HAT_TRICK = "hat-trick";
    public static final String SPEEDY = "speedy";
    public static final String WALL = "the-wall";
    public static final String UNSTOPPABLE = "unstoppable";
    public static final String MVP = "mvp";
    public static final String CLEAN_SHEET = "clean-sheet";
    public static final String VETERAN = "veteran";
    public static final String PARKOUR = "parkour";
    public static final String CLUTCH = "clutch";

    private final Plugin plugin;
    private final LogChannel log;
    private final Messages messages;
    private final Effects effects;
    private final StatsStore stats;
    private final ProfileStore profiles;
    private final Achievements achievements;
    private final LevelRule levels = new LevelRule();
    private final ChallengeRule challenges = new ChallengeRule();
    private final Map<UUID, ProfileStore.Profile> profileCache = new ConcurrentHashMap<>();
    private final Map<UUID, Long> xpCache = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Challenge, Integer>> todayCache = new ConcurrentHashMap<>();
    private volatile long cachedDay = -1;
    private volatile CtfSettings settings;

    public ProgressService(Plugin plugin, LogChannel log, Messages messages, Effects effects, StatsStore stats,
                           ProfileStore profiles, Achievements achievements, CtfSettings settings) {
        this.plugin = plugin;
        this.log = log;
        this.messages = messages;
        this.effects = effects;
        this.stats = stats;
        this.profiles = profiles;
        this.achievements = achievements;
        this.settings = settings;
    }

    @Override
    public void settings(CtfSettings settings) {
        this.settings = settings;
    }

    public LevelRule levelRule() {
        return levels;
    }

    // ------------------------------------------------------------------ achievements

    /** The achievements, defined where nobody has defined them (an owner's edit of one stays). */
    public void defineAchievements() {
        if (achievements == null) {
            return;
        }
        String owner = plugin.getName();
        define(Achievement.builder(owner, FIRST_CAPTURE).title("First Capture").icon(Material.RED_BANNER)
                .description("Bring a flag home for the first time.").points(10));
        define(Achievement.builder(owner, HAT_TRICK).title("Hat Trick").icon(Material.GOLDEN_HELMET)
                .description("Capture three flags in one round.").points(30));
        define(Achievement.builder(owner, SPEEDY).title("Speed Demon").icon(Material.FEATHER)
                .description("Capture a flag less than twenty seconds after taking it.").points(25));
        define(Achievement.builder(owner, WALL).title("The Wall").icon(Material.SHIELD)
                .description("Return five flags in one round.").points(30));
        define(Achievement.builder(owner, UNSTOPPABLE).title("Unstoppable").icon(Material.NETHERITE_SWORD)
                .description("Take down seven players without dying.").points(30));
        define(Achievement.builder(owner, MVP).title("Most Valuable").icon(Material.NETHER_STAR)
                .description("Be the MVP of a round.").points(20));
        define(Achievement.builder(owner, CLEAN_SHEET).title("Clean Sheet").icon(Material.DIAMOND_CHESTPLATE)
                .description("Win a round without your flag ever leaving home.").points(40));
        define(Achievement.builder(owner, VETERAN).title("Veteran").icon(Material.CLOCK)
                .description("Play a hundred rounds.").goal(100).points(50));
        define(Achievement.builder(owner, PARKOUR).title("Free Runner").icon(Material.LEATHER_BOOTS)
                .description("Finish the lobby parkour.").points(10));
        define(Achievement.builder(owner, CLUTCH).title("Denied!").icon(Material.BARRIER)
                .description("Stop a carrier a few steps from scoring.").points(20));
    }

    private void define(Achievement.Builder builder) {
        achievements.defineIfAbsent(builder.build());
    }

    public void award(UUID player, String id) {
        if (achievements != null && settings.achievements()) {
            achievements.award(player, plugin.getName() + ":" + id);
        }
    }

    public void progressAchievement(UUID player, String id, int by) {
        if (achievements != null && settings.achievements()) {
            achievements.progress(player, plugin.getName() + ":" + id, by);
        }
    }

    // ------------------------------------------------------------------ profile, xp, levels

    /** Reads what is kept about somebody into the cache. Off the server thread. */
    public void load(UUID player) {
        Scheduling.async(plugin, () -> {
            profileCache.put(player, profiles.of(player));
            xpCache.put(player, stats.of(player).map(Stats::xp).map(Integer::longValue).orElse(0L));
            todayCache.put(player, new EnumMap<>(profiles.challenges(player, today())));
        });
    }

    public ProfileStore.Profile profile(UUID player) {
        return profileCache.getOrDefault(player, ProfileStore.Profile.defaults());
    }

    public void pick(UUID player, java.util.function.UnaryOperator<ProfileStore.Profile> change) {
        ProfileStore.Profile next = change.apply(profile(player));
        profileCache.put(player, next);
        Scheduling.async(plugin, () -> profiles.save(player, next));
    }

    public long xp(UUID player) {
        return xpCache.getOrDefault(player, 0L);
    }

    public int level(UUID player) {
        return settings.levels() ? levels.level(xp(player)) : 99;
    }

    public boolean unlocked(UUID player, Trail trail) {
        return level(player) >= trail.level();
    }

    public boolean unlocked(UUID player, CaptureShow show) {
        return level(player) >= show.level();
    }

    /** Adds experience, says so, and says what a new level unlocked. Any thread. */
    public void addXp(UUID player, String name, int xp, boolean writeNow) {
        if (!settings.levels() || xp <= 0) {
            return;
        }
        long before = xp(player);
        long after = before + xp;
        xpCache.put(player, after);
        if (writeNow) {
            Scheduling.async(plugin, () -> stats.addXp(player, name, xp));
        }
        int was = levels.level(before);
        int now = levels.level(after);
        Player online = Bukkit.getPlayer(player);
        if (online == null) {
            return;
        }
        messages.send(online, "ctf.progress.xp", "xp", xp, "level", now);
        if (now > was) {
            messages.send(online, "ctf.progress.level-up", "level", now);
            effects.play(player, Cues.EARNED);
            for (Trail trail : Trail.values()) {
                if (trail.level() > was && trail.level() <= now) {
                    messages.send(online, "ctf.progress.unlocked", "what", "trail: " + trail.title());
                }
            }
            for (CaptureShow show : CaptureShow.values()) {
                if (show.level() > was && show.level() <= now) {
                    messages.send(online, "ctf.progress.unlocked", "what", "capture fireworks: " + show.title());
                }
            }
        }
    }

    // ------------------------------------------------------------------ challenges

    public static long today() {
        return LocalDate.now(ZoneId.systemDefault()).toEpochDay();
    }

    public List<Challenge> todays() {
        return challenges.today(LocalDate.ofEpochDay(today()), settings.challengesPerDay());
    }

    public int challengeDone(UUID player, Challenge challenge) {
        refreshDay();
        return todayCache.getOrDefault(player, Map.of()).getOrDefault(challenge, 0);
    }

    private void refreshDay() {
        long day = today();
        if (day != cachedDay) {
            cachedDay = day;
            todayCache.replaceAll((player, done) -> new EnumMap<>(Challenge.class));
        }
    }

    /** Something somebody did, for the challenges of the day. Any thread. */
    public void deed(UUID player, String name, Deed deed, Role role, int count) {
        if (!settings.challenges() || count <= 0) {
            return;
        }
        refreshDay();
        long day = today();
        for (Challenge challenge : todays()) {
            if (!counts(challenge, deed) || challenge.role().map(needed -> needed != role).orElse(false)) {
                continue;
            }
            Map<Challenge, Integer> done = todayCache.computeIfAbsent(player, id -> new EnumMap<>(Challenge.class));
            int before = done.getOrDefault(challenge, 0);
            ChallengeRule.Step step = challenges.step(challenge, before, count);
            if (step.now() == before) {
                continue;
            }
            done.put(challenge, step.now());
            Scheduling.async(plugin, () -> profiles.setChallenge(player, day, challenge, step.now()));
            if (step.justDone()) {
                Player online = Bukkit.getPlayer(player);
                if (online != null) {
                    messages.send(online, "ctf.progress.challenge-done", "challenge", challenge.describe(),
                            "xp", challenge.xp());
                }
                addXp(player, name, challenge.xp(), true);
            }
        }
    }

    private static boolean counts(Challenge challenge, Deed deed) {
        return switch (challenge) {
            case CAPTURE, SCOUT_CAPTURE -> deed == Deed.CAPTURE;
            case RETURN -> deed == Deed.RETURN;
            case KILL, ARCHER_KILL -> deed == Deed.KILL;
            case CARRIER_KILL -> deed == Deed.CARRIER_KILL;
            case WIN -> deed == Deed.WIN;
            case POWER_UP -> deed == Deed.POWER_UP;
            case ESCORT -> deed == Deed.ESCORT;
            case MEDIC_PLAY -> deed == Deed.PLAYED;
        };
    }

    // ------------------------------------------------------------------ records

    /** A round's result for one player, written off the server thread. */
    public void record(StatsStore.Delta delta) {
        Scheduling.async(plugin, () -> {
            if (!stats.add(delta, LocalDate.now(ZoneId.systemDefault()).toEpochDay() / 7)) {
                log.warn("The result of {} could not be written.", delta.name());
            }
        });
    }

    // ------------------------------------------------------------------ money

    public Optional<Economy> economy() {
        return Economies.current();
    }

    /** Pays somebody a whole number of the currency's units. Nothing without an economy or for 0. */
    public void pay(UUID player, int units, String reason) {
        if (units <= 0) {
            return;
        }
        economy().ifPresent(economy -> {
            Money amount = Money.of(units * economy.currency().unit());
            Scheduling.async(plugin, () -> economy.deposit(player, amount, reason, plugin.getName()));
        });
    }

    /** Takes an entry fee; false if they cannot pay it. Any thread, answers at once. */
    public boolean charge(UUID player, int units) {
        if (units <= 0) {
            return true;
        }
        Optional<Economy> economy = economy();
        if (economy.isEmpty()) {
            return true;
        }
        Money amount = Money.of(units * economy.get().currency().unit());
        return economy.get().withdraw(player, amount, "Capture the Flag entry", plugin.getName()).succeeded();
    }

    /** Gives the fees back — a round that never started. */
    public void refund(UUID player, int units) {
        economy().ifPresent(economy -> {
            if (units > 0) {
                economy.refund(player, Money.of(units * economy.currency().unit()), "Capture the Flag entry back",
                        plugin.getName());
            }
        });
    }

    /** Shares a pot of fees among the winners. */
    public void sharePot(int fees, List<UUID> winners) {
        Optional<Economy> economy = economy();
        if (fees <= 0 || winners.isEmpty() || economy.isEmpty()) {
            return;
        }
        Money pot = Money.of(fees * economy.get().currency().unit());
        new PrizePot().payouts(pot, settings.houseCut(), "100", List.of(winners)).forEach((winner, share) ->
                Scheduling.async(plugin, () -> economy.get().deposit(winner, share, "Capture the Flag winnings",
                        plugin.getName())));
    }

    public String format(int units) {
        return economy().map(economy -> economy.format(Money.of(units * economy.currency().unit())))
                .orElse(String.valueOf(units));
    }

    public void forget(UUID player) {
        profileCache.remove(player);
        xpCache.remove(player);
        todayCache.remove(player);
    }
}
