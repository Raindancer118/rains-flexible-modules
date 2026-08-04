package de.raindancer.modules.homes;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.Material;

/**
 * What an owner can decide about homes.
 *
 * <h2>Every {@code @Key} is the path the old plugin used</h2>
 * Not a preference — the whole point. An upgrading server has
 * {@code plugins/RainsHomes/config.yml} with {@code homes.warmup-seconds: 5} in it, and a key derived
 * from the Java name would be {@code warmup-seconds} under a different root, so the 5 would be read
 * as absent and silently replaced by the shipped 3. Every path below is the one
 * {@code HomeOptions.from} read.
 *
 * <p>The record <em>is</em> the schema: the file, its comments, its validation and the
 * {@code /settings} screens all come from it, and {@link #DEFAULTS} is real Java rather than literals
 * in a yaml.
 */
@Settings(id = "homes", topics = {
        @Topic(path = "homes", title = "Homes", icon = Material.RED_BED),
        @Topic(path = "homes/travelling", title = "Going home", icon = Material.ENDER_PEARL),
        @Topic(path = "homes/keeping", title = "Keeping them", icon = Material.OAK_DOOR),
})
public record HomeSettings(

        @In("homes/keeping") @Title("Homes a player may have") @Range(min = 0, max = 100)
        @Describe("The floor, which a permission can raise but never lower: homes.limit.10 gives "
                + "that player ten. Zero switches homes off for anybody who has not been granted "
                + "one of those.")
        @Key("homes.max")
        int max,

        @In("homes/travelling") @Title("A home may be in another world")
        @Describe("Whether going home may cross a world boundary. Off, a home in the nether is only "
                + "reachable from the nether — which is one way to keep a mining world from being a "
                + "free trip back to spawn.")
        @Key("homes.allow-cross-world")
        boolean allowCrossWorld,

        @In("homes/travelling") @Title("Stand still for") @Range(min = 0, max = 60)
        @Describe("Seconds before going home takes somebody, so that running out of a fight through "
                + "a home costs something. Zero sends them at once.")
        @Key("homes.warmup-seconds")
        int warmupSeconds,

        @In("homes/travelling") @Title("Moving cancels the wait")
        @Describe("Whether leaving the block you started on gives up on the teleport. Turning and "
                + "looking around never counts — only leaving the block.")
        @Key("homes.cancel-on-move")
        boolean cancelOnMove,

        @In("homes/travelling") @Title("Being hurt cancels the wait")
        @Describe("Whether taking damage gives up on the teleport. Worth thinking about together "
                + "with the wait above: mobs where people stand and a five-second wait is a home "
                + "nobody can reach, and that gets reported as 'homes are broken'.")
        @Key("homes.cancel-on-damage")
        boolean cancelOnDamage,

        @In("homes/travelling") @Title("Wait between going home") @Range(min = 0, max = 3600)
        @Describe("Seconds before somebody may go home again. The wait starts when they arrive, so "
                + "a teleport they were knocked out of costs them nothing. Zero switches it off.")
        @Key("homes.cooldown-seconds")
        int cooldownSeconds,

        @In("homes/keeping") @Title("Operators skip the waits")
        @Describe("Whether being an operator counts as holding the bypass permissions and the "
                + "unlimited one, without being granted them. Off by default, and deliberately: an "
                + "admin who silently bypasses a feature is the one person who cannot test it.")
        @Key("homes.operators-bypass")
        boolean operatorsBypass,

        @In("homes/travelling") @Title("Look for somewhere safe to land")
        @Describe("Whether arriving looks for solid ground near the home rather than dropping "
                + "somebody exactly where it was set. A home whose floor has since been mined out, "
                + "or one set on a boat, is otherwise a fall.")
        @Key("homes.safe-arrival")
        boolean safeArrival,

        @In("homes/travelling") @Title("Bring what you are leading")
        @Describe("Whether the dog on your lead, the boat you are towing and whatever is riding "
                + "with you come home too. Never another player, and never somebody else's animal.")
        @Key("homes.bring-what-you-lead")
        boolean bringWhatYouLead) {

    /**
     * What the old plugin shipped, plus the two that are new.
     *
     * <p>The first seven are {@code HomeOptions.defaults()} exactly — {@code (3, true, 3, true,
     * true, 0, false)} — so a server that upgrades and never touches the file behaves as it did.
     * {@code safeArrival} and {@code bringWhatYouLead} are new and default on: neither existed to be
     * configured, and both are what somebody would expect if asked.
     */
    public static final HomeSettings DEFAULTS =
            new HomeSettings(3, true, 3, true, true, 0, false, true, true);

    // ------------------------------------------------------------------ read back safely

    /**
     * The configured floor, clamped.
     *
     * <p>The store clamps what it reads from the file, but a {@code HomeSettings} can also be built
     * in code — by a test, or by a host handing in its own — and this is what stops a negative
     * ceiling meaning "minus three homes".
     */
    public int homeLimit() {
        return Math.max(0, Math.min(100, max));
    }

    public int warmup() {
        return Math.max(0, Math.min(60, warmupSeconds));
    }

    public int cooldown() {
        return Math.max(0, Math.min(3600, cooldownSeconds));
    }

    // ------------------------------------------------------------------ one component at a time

    public HomeSettings withMax(int homes) {
        return new HomeSettings(homes, allowCrossWorld, warmupSeconds, cancelOnMove, cancelOnDamage,
                cooldownSeconds, operatorsBypass, safeArrival, bringWhatYouLead);
    }

    public HomeSettings withAllowCrossWorld(boolean allow) {
        return new HomeSettings(max, allow, warmupSeconds, cancelOnMove, cancelOnDamage,
                cooldownSeconds, operatorsBypass, safeArrival, bringWhatYouLead);
    }

    public HomeSettings withWarmupSeconds(int seconds) {
        return new HomeSettings(max, allowCrossWorld, seconds, cancelOnMove, cancelOnDamage,
                cooldownSeconds, operatorsBypass, safeArrival, bringWhatYouLead);
    }

    public HomeSettings withCancelOnMove(boolean cancels) {
        return new HomeSettings(max, allowCrossWorld, warmupSeconds, cancels, cancelOnDamage,
                cooldownSeconds, operatorsBypass, safeArrival, bringWhatYouLead);
    }

    public HomeSettings withCancelOnDamage(boolean cancels) {
        return new HomeSettings(max, allowCrossWorld, warmupSeconds, cancelOnMove, cancels,
                cooldownSeconds, operatorsBypass, safeArrival, bringWhatYouLead);
    }

    public HomeSettings withCooldownSeconds(int seconds) {
        return new HomeSettings(max, allowCrossWorld, warmupSeconds, cancelOnMove, cancelOnDamage,
                seconds, operatorsBypass, safeArrival, bringWhatYouLead);
    }

    public HomeSettings withOperatorsBypass(boolean bypass) {
        return new HomeSettings(max, allowCrossWorld, warmupSeconds, cancelOnMove, cancelOnDamage,
                cooldownSeconds, bypass, safeArrival, bringWhatYouLead);
    }

    public HomeSettings withSafeArrival(boolean safe) {
        return new HomeSettings(max, allowCrossWorld, warmupSeconds, cancelOnMove, cancelOnDamage,
                cooldownSeconds, operatorsBypass, safe, bringWhatYouLead);
    }

    public HomeSettings withBringWhatYouLead(boolean bring) {
        return new HomeSettings(max, allowCrossWorld, warmupSeconds, cancelOnMove, cancelOnDamage,
                cooldownSeconds, operatorsBypass, safeArrival, bring);
    }
}
