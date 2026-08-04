package de.raindancer.modules.farmworld;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import de.raindancer.core.world.teleport.Companions;
import de.raindancer.modules.farmworld.model.Scatter;
import org.bukkit.Material;

import java.time.Duration;

/**
 * What an owner can decide about farm worlds.
 *
 * <p>The record <em>is</em> the schema: the file, its comments, its validation and the
 * {@code /settings} screens all come from it, so there is nothing to keep in step and no second list to
 * forget. {@link #DEFAULTS} is real Java rather than literals in a yaml, and every component has a
 * {@code with…} of its own — a positional constructor with twelve components is a mis-ordering waiting
 * to happen, and two swapped {@code int}s compile perfectly.
 *
 * <h2>The three things deliberately not here</h2>
 * <ul>
 *   <li><b>Which farm worlds there are, and how often each is thrown away.</b> That is per farm world
 *       and it is Core's {@code farmworlds.yml} — a server has one farm world regenerated weekly and
 *       another kept for ever, so a single number in this file could not express it.</li>
 *   <li><b>Whether to look for somewhere safe to land.</b> Arriving unchecked at a random point of
 *       generated terrain is arriving inside stone, in lava, or eighty blocks above the ground. A
 *       switch for that is a switch whose off position is a death, so there is not one.</li>
 *   <li><b>How many farm worlds a server may have, and how long a name may be.</b> Both in
 *       {@code FarmWorldNameRule}, with the reasons.</li>
 * </ul>
 */
@Settings(id = "farmworlds", topics = {
        @Topic(path = "farmworlds", title = "Farm worlds", icon = Material.GRASS_BLOCK),
        @Topic(path = "farmworlds/travelling", title = "Getting there", icon = Material.ENDER_PEARL),
        @Topic(path = "farmworlds/arriving", title = "Where you land", icon = Material.FILLED_MAP),
        @Topic(path = "farmworlds/regenerating", title = "Being warned", icon = Material.BELL),
})
public record FarmWorldSettings(

        @In("farmworlds/travelling") @Title("Stand still for") @Range(min = 0, max = 60)
        @Describe("Seconds somebody must stand still before the farm world takes them, so that "
                + "escaping a fight into the farm world costs something. Zero sends them at once.")
        @Key("warmup-seconds")
        int warmupSeconds,

        @In("farmworlds/travelling") @Title("Wait between trips") @Range(min = 0, max = 86400)
        @Describe("Seconds between one player's trips to a farm world. Worth more here than for a "
                + "warp: without it, arriving somewhere unpromising and going straight back for "
                + "another roll of the dice is free, and then the wait is the only thing that makes "
                + "where you landed matter. Zero switches it off.")
        @Key("cooldown-seconds")
        int cooldownSeconds,

        @In("farmworlds/travelling") @Title("Being hurt cancels the wait")
        @Describe("Whether taking damage gives up on a trip somebody is standing still for. Worth "
                + "thinking about together with the wait above: mobs at spawn and a five-second "
                + "wait is a trip nobody can complete, and that gets reported as 'the farm world is "
                + "broken'.")
        @Key("hurt-cancels-warmup")
        boolean hurtCancelsWarmup,

        @In("farmworlds/arriving") @Title("How far to look for solid ground") @Range(min = 1, max = 32)
        @Describe("Blocks. Somewhere safe is always looked for — there is no switch for that, because "
                + "a random point in generated terrain is inside stone about as often as it is on "
                + "grass. This is only how far sideways to give up, and it is also how much world "
                + "has to be generated to find out, so a large number is a pause for everybody.")
        @Key("safe-arrival-radius")
        int safeArrivalRadius,

        @In("farmworlds/travelling") @Title("Bring what you are leading")
        @Describe("Whether a dog on your lead, the boat you are towing and whatever is riding with "
                + "you come along. Off, a trip takes the player and leaves everything they were "
                + "holding on to standing where they were.")
        @Key("bring-what-you-lead")
        boolean bringWhatYouLead,

        @In("farmworlds/travelling") @Title("Bring your animals standing nearby")
        @Describe("Whether your own tame animals come along as well, even the ones not on a lead. "
                + "Friendlier, and it costs a search around the player on every trip. Never brings "
                + "somebody else's animals, and never brings a wild mob.")
        @Key("bring-nearby-pets")
        boolean bringNearbyPets,

        @In("farmworlds/travelling") @Title("How far your animals may be") @Range(min = 1, max = 32)
        @Describe("Blocks. Only used when the setting above is on. A lead is never limited by this: "
                + "something on a lead comes however far it has drifted.")
        @Key("bring-radius")
        int bringRadius,

        @In("farmworlds/travelling") @Title("Most that may come at once") @Range(min = 1, max = 20)
        @Describe("A ceiling on how many animals travel with one player. A hundred entities "
                + "teleported at once is a pause for everybody on the server, and somebody will try "
                + "it. What is on a lead is brought first when this bites.")
        @Key("bring-at-most")
        int bringAtMost,

        @In("farmworlds/arriving") @Title("Scatter arrivals")
        @Describe("Whether everybody lands somewhere different. This is what makes a farm world one: "
                + "sent to the same spot, the ground around it is bare within a day and every "
                + "arrival after that is a five-minute walk before they can start. Off, everybody "
                + "arrives at the world's own spawn.")
        @Key("scatter-arrivals")
        boolean scatterArrivals,

        @In("farmworlds/arriving") @Title("Nearest anybody lands to the middle")
        @Range(min = 0, max = 100000)
        @Describe("Blocks. Not zero by default: the middle is where the portals, the roads and "
                + "whatever an admin built are, and that is the one part of a farm world worth "
                + "keeping intact.")
        @Key("scatter-nearest")
        int scatterNearest,

        @In("farmworlds/arriving") @Title("Furthest anybody lands from the middle")
        @Range(min = 16, max = 100000)
        @Describe("Blocks. Also how much of the world gets generated over the farm world's life, one "
                + "arrival at a time — which is disk nobody notices until it runs out. Kept inside "
                + "the world's border automatically, so a border smaller than this wins.")
        @Key("scatter-furthest")
        int scatterFurthest,

        @In("farmworlds/regenerating") @Title("Warn this long before") @Range(min = 0, max = 1440)
        @Describe("Minutes of notice before a farm world is thrown away, announced to the whole "
                + "server. Five minutes and one minute are always announced as well, whatever this "
                + "says: those two are 'start walking back' and 'put it somewhere it survives', and "
                + "an hour's notice given once is notice nobody remembers hearing. Zero leaves only "
                + "those two.")
        @Key("warn-minutes")
        int warnMinutes) {

    public static final FarmWorldSettings DEFAULTS = new FarmWorldSettings(
            5, 60, true, 8, true, false, 8, 10, true, 250, 4000, 15);

    // ------------------------------------------------------------------ read back safely

    /**
     * The warm-up, clamped.
     *
     * <p>The store clamps what it reads from the file, but a {@code FarmWorldSettings} can also be built
     * in code — by a test, or by a host handing in its own — and the clamp is what stops a negative
     * warm-up becoming a countdown that never ends.
     */
    public int warmup() {
        return Math.max(0, Math.min(60, warmupSeconds));
    }

    public int cooldown() {
        return Math.max(0, Math.min(86_400, cooldownSeconds));
    }

    /** The wait between trips, as the duration Core's {@code Cooldowns} takes. */
    public Duration cooldownFor() {
        return Duration.ofSeconds(cooldown());
    }

    public int arrivalRadius() {
        return Math.max(1, Math.min(32, safeArrivalRadius));
    }

    /**
     * How far ahead the owner's own warning goes.
     *
     * <p>Zero is not "no warning at all": the five- and one-minute notices are given regardless. See
     * {@code NoticeRule}.
     */
    public Duration warnLead() {
        return Duration.ofMinutes(Math.max(0, Math.min(1440, warnMinutes)));
    }

    /**
     * Where somebody lands, as the policy the arrival is built from.
     *
     * <p>Built here rather than stored, because three values in a config file and one policy in code are
     * the same decision written twice — and the copy in the file is the one an owner edits. The
     * normalising is {@link Scatter}'s own, so two numbers typed the wrong way round are the ring they
     * obviously mean rather than an exception at the first arrival.
     */
    public Scatter scatter() {
        return new Scatter(scatterArrivals, scatterNearest, scatterFurthest);
    }

    /**
     * What travels with the player, as the policy Core's teleport takes.
     *
     * <p>The clamping is {@code Companions}' own, so a radius typed as a thousand is the largest sensible
     * search rather than an exception at the first trip.
     */
    public Companions companions() {
        if (!bringWhatYouLead) {
            return Companions.NOBODY;
        }
        Companions policy = bringNearbyPets
                ? Companions.WHAT_YOU_LEAD_AND_NEARBY_PETS
                : Companions.WHAT_YOU_LEAD;
        return policy.within(bringRadius).atMost(bringAtMost);
    }

    // ------------------------------------------------------------------ one component at a time

    public FarmWorldSettings withWarmupSeconds(int seconds) {
        return new FarmWorldSettings(seconds, cooldownSeconds, hurtCancelsWarmup, safeArrivalRadius, bringWhatYouLead, bringNearbyPets, bringRadius, bringAtMost, scatterArrivals, scatterNearest, scatterFurthest, warnMinutes);
    }

    public FarmWorldSettings withCooldownSeconds(int seconds) {
        return new FarmWorldSettings(warmupSeconds, seconds, hurtCancelsWarmup, safeArrivalRadius, bringWhatYouLead, bringNearbyPets, bringRadius, bringAtMost, scatterArrivals, scatterNearest, scatterFurthest, warnMinutes);
    }

    public FarmWorldSettings withHurtCancelsWarmup(boolean cancels) {
        return new FarmWorldSettings(warmupSeconds, cooldownSeconds, cancels, safeArrivalRadius, bringWhatYouLead, bringNearbyPets, bringRadius, bringAtMost, scatterArrivals, scatterNearest, scatterFurthest, warnMinutes);
    }

    public FarmWorldSettings withSafeArrivalRadius(int radius) {
        return new FarmWorldSettings(warmupSeconds, cooldownSeconds, hurtCancelsWarmup, radius, bringWhatYouLead, bringNearbyPets, bringRadius, bringAtMost, scatterArrivals, scatterNearest, scatterFurthest, warnMinutes);
    }

    public FarmWorldSettings withBringWhatYouLead(boolean bring) {
        return new FarmWorldSettings(warmupSeconds, cooldownSeconds, hurtCancelsWarmup, safeArrivalRadius, bring, bringNearbyPets, bringRadius, bringAtMost, scatterArrivals, scatterNearest, scatterFurthest, warnMinutes);
    }

    public FarmWorldSettings withBringNearbyPets(boolean bring) {
        return new FarmWorldSettings(warmupSeconds, cooldownSeconds, hurtCancelsWarmup, safeArrivalRadius, bringWhatYouLead, bring, bringRadius, bringAtMost, scatterArrivals, scatterNearest, scatterFurthest, warnMinutes);
    }

    public FarmWorldSettings withBringRadius(int radius) {
        return new FarmWorldSettings(warmupSeconds, cooldownSeconds, hurtCancelsWarmup, safeArrivalRadius, bringWhatYouLead, bringNearbyPets, radius, bringAtMost, scatterArrivals, scatterNearest, scatterFurthest, warnMinutes);
    }

    public FarmWorldSettings withBringAtMost(int most) {
        return new FarmWorldSettings(warmupSeconds, cooldownSeconds, hurtCancelsWarmup, safeArrivalRadius, bringWhatYouLead, bringNearbyPets, bringRadius, most, scatterArrivals, scatterNearest, scatterFurthest, warnMinutes);
    }

    public FarmWorldSettings withScatterArrivals(boolean scatter) {
        return new FarmWorldSettings(warmupSeconds, cooldownSeconds, hurtCancelsWarmup, safeArrivalRadius, bringWhatYouLead, bringNearbyPets, bringRadius, bringAtMost, scatter, scatterNearest, scatterFurthest, warnMinutes);
    }

    public FarmWorldSettings withScatterNearest(int blocks) {
        return new FarmWorldSettings(warmupSeconds, cooldownSeconds, hurtCancelsWarmup, safeArrivalRadius, bringWhatYouLead, bringNearbyPets, bringRadius, bringAtMost, scatterArrivals, blocks, scatterFurthest, warnMinutes);
    }

    public FarmWorldSettings withScatterFurthest(int blocks) {
        return new FarmWorldSettings(warmupSeconds, cooldownSeconds, hurtCancelsWarmup, safeArrivalRadius, bringWhatYouLead, bringNearbyPets, bringRadius, bringAtMost, scatterArrivals, scatterNearest, blocks, warnMinutes);
    }

    public FarmWorldSettings withWarnMinutes(int minutes) {
        return new FarmWorldSettings(warmupSeconds, cooldownSeconds, hurtCancelsWarmup, safeArrivalRadius, bringWhatYouLead, bringNearbyPets, bringRadius, bringAtMost, scatterArrivals, scatterNearest, scatterFurthest, minutes);
    }
}
