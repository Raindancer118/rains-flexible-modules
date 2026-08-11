package de.raindancer.modules.rtp;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import de.raindancer.core.world.protection.FlagPolicy;
import de.raindancer.core.world.teleport.Scatter;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.List;

/**
 * What an owner can decide about random teleporting.
 *
 * <p>The record <em>is</em> the schema: the file, its comments, its validation and the
 * {@code /settings} screens all come from it, so there is nothing to keep in step and no second list
 * to forget. {@link #DEFAULTS} is real Java rather than literals in a yaml, and every component has a
 * {@code with…} of its own — a positional constructor with several {@code int}s in a row is a
 * mis-ordering waiting to happen.
 */
@Settings(id = "rtp", topics = {
        @Topic(path = "rtp", title = "Random teleport", icon = Material.ENDER_PEARL),
        @Topic(path = "rtp/travelling", title = "Going there", icon = Material.CLOCK),
        @Topic(path = "rtp/where", title = "Where somebody may land", icon = Material.MAP),
        @Topic(path = "rtp/pool", title = "Getting ready ahead of time", icon = Material.CHEST),
})
public record RtpSettings(

        @In("rtp/travelling") @Title("Stand still for") @Range(min = 0, max = 60)
        @Describe("Seconds somebody must stand still before they are sent, so that running away "
                + "from a fight this way costs something. Zero sends them at once.")
        @Key("rtp-warmup-seconds")
        int warmupSeconds,

        @In("rtp/travelling") @Title("Wait between goes") @Range(min = 0, max = 86400)
        @Describe("Seconds before the same player may do this again. Zero switches it off.")
        @Key("rtp-cooldown-seconds")
        int cooldownSeconds,

        @In("rtp/travelling") @Title("Being hurt cancels the wait")
        @Describe("Whether taking damage gives up on a trip somebody is standing still for.")
        @Key("rtp-hurt-cancels-warmup")
        boolean hurtCancelsWarmup,

        @In("rtp/where") @Title("Nearest anybody lands to the middle") @Range(min = 0, max = 100000)
        @Describe("Blocks. Not zero by default: the middle is usually spawn, where the roads and "
                + "whatever an admin built are, and landing right on top of it is not what 'random' "
                + "is for.")
        @Key("min-radius")
        int minRadius,

        @In("rtp/where") @Title("Furthest anybody lands from the middle") @Range(min = 16, max = 100000)
        @Describe("Blocks. Also how much of the world gets generated over time, one arrival at a "
                + "time — which is disk nobody notices until it runs out. Kept inside the world's "
                + "border automatically, so a border smaller than this wins.")
        @Key("max-radius")
        int maxRadius,

        @In("rtp/where") @Title("Who decides whether a landing is checked for safety")
        @Describe("AVAILABLE lets each player choose, per trip, when they ask. FORCED_ON always "
                + "checks; FORCED_OFF (or DISABLED) never does, and a random point nobody has looked "
                + "at — including straight into the void, if that is where it lands — is exactly what "
                + "arrives. That is not a bug to fix; some servers want random teleport to be able to "
                + "kill you.")
        @Key("safe-arrival-policy")
        FlagPolicy safeArrivalPolicy,

        @In("rtp/where") @Title("How far to look for solid ground") @Range(min = 1, max = 32)
        @Describe("Blocks. A random point is a point nobody has looked at, so a checked arrival "
                + "searches for somewhere safe near it rather than dropping somebody wherever that "
                + "turned out to be. Also how much of the world is loaded to find out, so a large "
                + "number is a pause for everybody, not only the person going.")
        @Key("rtp-safe-arrival-radius")
        int safeArrivalRadius,

        @In("rtp/where") @Title("How uneven the landing may be") @Range(min = 0, max = 10)
        @Describe("Blocks. A checked arrival refuses the bottom of a ravine or a cave mouth even "
                + "though it is technically safe, by comparing its height against the ground right "
                + "beside it — this is how much they may differ and still count as 'the same place'. "
                + "Zero demands an exact match, which on rolling terrain can mean searching further "
                + "out than usual.")
        @Key("height-tolerance")
        int heightTolerance,

        @In("rtp/where") @Title("Centre on the player instead of spawn")
        @Describe("Off scatters around the world's spawn, wherever the generator put it. On scatters "
                + "around wherever the player is standing when they ask — useful for a server where "
                + "spawn is not the middle of anything.")
        @Key("centre-on-player")
        boolean centreOnPlayer,

        @In("rtp/where") @Title("Worlds this does not work in")
        @Describe("Names, comma separated. A minigame arena or a lobby is not somewhere a random "
                + "point is ever wanted.")
        @Key("disabled-worlds")
        List<String> disabledWorlds,

        @In("rtp/pool") @Title("Prepare locations ahead of time")
        @Describe("Off searches for a landing at the moment somebody asks, exactly as if this whole "
                + "page did not exist. On keeps a pool of already-checked spots ready, so most trips "
                + "skip the search entirely — each one is still re-checked the moment it is actually "
                + "used, since the ground under it can change between being found and being given out.")
        @Key("pool-enabled")
        boolean poolEnabled,

        @In("rtp/pool") @Title("At least this many prepared every day") @Range(min = 0, max = 1000)
        @Describe("However many the pool is short of this by, found once a day, per world this runs "
                + "in. Zero switches the daily top-up off without switching the pool itself off — an "
                + "owner who only ever prepares by hand still gets to use one.")
        @Key("pool-daily-minimum")
        int poolDailyMinimum,

        @In("rtp/pool") @Title("Never keep more than this many ready") @Range(min = 0, max = 20000)
        @Describe("Across every world this runs in combined. Each one is a handful of bytes on disk, "
                + "so the ceiling exists for the search it would otherwise cost to keep filling a pool "
                + "nobody is emptying, not for the space.")
        @Key("pool-max-size")
        int poolMaxSize) {

    public RtpSettings {
        disabledWorlds = disabledWorlds == null ? List.of() : List.copyOf(disabledWorlds);
        safeArrivalPolicy = safeArrivalPolicy == null ? FlagPolicy.AVAILABLE : safeArrivalPolicy;
    }

    public static final RtpSettings DEFAULTS = new RtpSettings(
            3, 30, true, 100, 5000, FlagPolicy.AVAILABLE, 8, 1, false, List.of(),
            true, 40, 3000);

    // ------------------------------------------------------------------ read back safely

    /** The warm-up, clamped. See {@code WarpSettings#warmup} for why this exists as well as the store's own clamp. */
    public int warmup() {
        return Math.max(0, Math.min(60, warmupSeconds));
    }

    public int cooldown() {
        return Math.max(0, Math.min(86400, cooldownSeconds));
    }

    public int arrivalRadius() {
        return Math.max(1, Math.min(32, safeArrivalRadius));
    }

    public int tolerance() {
        return Math.max(0, Math.min(10, heightTolerance));
    }

    /**
     * The ring somebody lands in, as the policy Core's {@link Scatter} takes.
     *
     * <p>Built here rather than stored, because two numbers in a config file and one policy in code
     * are the same decision written twice — and the copy in the file is the one an owner edits.
     * Always {@code enabled}: unlike the farm worlds, where scattering can be switched off in favour
     * of a fixed spawn, a random teleport that does not scatter is not a random teleport.
     */
    public Scatter scatter() {
        return new Scatter(true, minRadius, maxRadius);
    }

    /**
     * The same, kept inside this world's own border.
     *
     * <p>A border nobody has narrowed is Bukkit's own huge default, and {@link Scatter#within} is a
     * no-op whenever the border is wider than the configured ring anyway — so there is no need to tell
     * the default apart from a real one here. One method rather than two call sites each doing the same
     * three lines: a live trip and the pool preparing one ahead of time both want exactly this.
     */
    public Scatter scatterWithin(World world) {
        if (world == null) {
            return scatter();
        }
        double size = world.getWorldBorder().getSize();
        return scatter().within((int) Math.min(Integer.MAX_VALUE, size / 2));
    }

    /** How many the pool is topped up by at once, clamped. */
    public int dailyMinimum() {
        return Math.max(0, Math.min(1000, poolDailyMinimum));
    }

    /** How many the pool may hold in total, clamped. */
    public int maxPoolSize() {
        return Math.max(0, Math.min(20000, poolMaxSize));
    }

    /** Whether this world is switched off, by name and case-insensitively. */
    public boolean isDisabled(String world) {
        if (world == null) {
            return false;
        }
        for (String disabled : disabledWorlds) {
            if (disabled.equalsIgnoreCase(world)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ one component at a time

    public RtpSettings withWarmupSeconds(int seconds) {
        return new RtpSettings(seconds, cooldownSeconds, hurtCancelsWarmup, minRadius, maxRadius,
                safeArrivalPolicy, safeArrivalRadius, heightTolerance, centreOnPlayer, disabledWorlds, poolEnabled, poolDailyMinimum, poolMaxSize);
    }

    public RtpSettings withCooldownSeconds(int seconds) {
        return new RtpSettings(warmupSeconds, seconds, hurtCancelsWarmup, minRadius, maxRadius,
                safeArrivalPolicy, safeArrivalRadius, heightTolerance, centreOnPlayer, disabledWorlds, poolEnabled, poolDailyMinimum, poolMaxSize);
    }

    public RtpSettings withHurtCancelsWarmup(boolean cancels) {
        return new RtpSettings(warmupSeconds, cooldownSeconds, cancels, minRadius, maxRadius,
                safeArrivalPolicy, safeArrivalRadius, heightTolerance, centreOnPlayer, disabledWorlds, poolEnabled, poolDailyMinimum, poolMaxSize);
    }

    public RtpSettings withMinRadius(int radius) {
        return new RtpSettings(warmupSeconds, cooldownSeconds, hurtCancelsWarmup, radius, maxRadius,
                safeArrivalPolicy, safeArrivalRadius, heightTolerance, centreOnPlayer, disabledWorlds, poolEnabled, poolDailyMinimum, poolMaxSize);
    }

    public RtpSettings withMaxRadius(int radius) {
        return new RtpSettings(warmupSeconds, cooldownSeconds, hurtCancelsWarmup, minRadius, radius,
                safeArrivalPolicy, safeArrivalRadius, heightTolerance, centreOnPlayer, disabledWorlds, poolEnabled, poolDailyMinimum, poolMaxSize);
    }

    public RtpSettings withSafeArrivalPolicy(FlagPolicy policy) {
        return new RtpSettings(warmupSeconds, cooldownSeconds, hurtCancelsWarmup, minRadius, maxRadius,
                policy, safeArrivalRadius, heightTolerance, centreOnPlayer, disabledWorlds, poolEnabled, poolDailyMinimum, poolMaxSize);
    }

    public RtpSettings withSafeArrivalRadius(int radius) {
        return new RtpSettings(warmupSeconds, cooldownSeconds, hurtCancelsWarmup, minRadius, maxRadius,
                safeArrivalPolicy, radius, heightTolerance, centreOnPlayer, disabledWorlds, poolEnabled, poolDailyMinimum, poolMaxSize);
    }

    public RtpSettings withHeightTolerance(int tolerance) {
        return new RtpSettings(warmupSeconds, cooldownSeconds, hurtCancelsWarmup, minRadius, maxRadius,
                safeArrivalPolicy, safeArrivalRadius, tolerance, centreOnPlayer, disabledWorlds, poolEnabled, poolDailyMinimum, poolMaxSize);
    }

    public RtpSettings withCentreOnPlayer(boolean centred) {
        return new RtpSettings(warmupSeconds, cooldownSeconds, hurtCancelsWarmup, minRadius, maxRadius,
                safeArrivalPolicy, safeArrivalRadius, heightTolerance, centred, disabledWorlds,
                poolEnabled, poolDailyMinimum, poolMaxSize);
    }

    public RtpSettings withDisabledWorlds(List<String> worlds) {
        return new RtpSettings(warmupSeconds, cooldownSeconds, hurtCancelsWarmup, minRadius, maxRadius,
                safeArrivalPolicy, safeArrivalRadius, heightTolerance, centreOnPlayer, worlds,
                poolEnabled, poolDailyMinimum, poolMaxSize);
    }

    public RtpSettings withPoolEnabled(boolean enabled) {
        return new RtpSettings(warmupSeconds, cooldownSeconds, hurtCancelsWarmup, minRadius, maxRadius,
                safeArrivalPolicy, safeArrivalRadius, heightTolerance, centreOnPlayer, disabledWorlds,
                enabled, poolDailyMinimum, poolMaxSize);
    }

    public RtpSettings withPoolDailyMinimum(int minimum) {
        return new RtpSettings(warmupSeconds, cooldownSeconds, hurtCancelsWarmup, minRadius, maxRadius,
                safeArrivalPolicy, safeArrivalRadius, heightTolerance, centreOnPlayer, disabledWorlds,
                poolEnabled, minimum, poolMaxSize);
    }

    public RtpSettings withPoolMaxSize(int maxSize) {
        return new RtpSettings(warmupSeconds, cooldownSeconds, hurtCancelsWarmup, minRadius, maxRadius,
                safeArrivalPolicy, safeArrivalRadius, heightTolerance, centreOnPlayer, disabledWorlds,
                poolEnabled, poolDailyMinimum, maxSize);
    }
}
