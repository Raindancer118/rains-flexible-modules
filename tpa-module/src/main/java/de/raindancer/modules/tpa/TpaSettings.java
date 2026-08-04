package de.raindancer.modules.tpa;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.Material;

/**
 * What an owner can decide about teleport requests.
 *
 * <h2>Every {@code @Key} is the path the old plugin used</h2>
 * Not a preference — the whole point. An upgrading server has {@code plugins/RainsTPA/config.yml} with
 * {@code tpa.request-seconds: 120} in it, and a key derived from the Java name would be read as absent
 * and silently replaced by the shipped 60. Every path below is the one {@code TpaOptions.from} read.
 */
@Settings(id = "tpa", topics = {
        @Topic(path = "tpa", title = "Teleport requests", icon = Material.ENDER_PEARL),
        @Topic(path = "tpa/asking", title = "Asking", icon = Material.PAPER),
        @Topic(path = "tpa/travelling", title = "Going", icon = Material.ENDER_EYE),
        @Topic(path = "tpa/back", title = "Going back", icon = Material.COMPASS),
})
public record TpaSettings(

        @In("tpa/asking") @Title("A request stands for") @Range(min = 5, max = 600)
        @Describe("Seconds before an unanswered request lapses. Both people are told when it does — "
                + "a request that vanishes without a word is one somebody goes on waiting to answer.")
        @Key("tpa.request-seconds")
        int requestSeconds,

        @In("tpa/travelling") @Title("Stand still for") @Range(min = 0, max = 60)
        @Describe("Seconds the traveller must stand still, so a teleport request is not a way out of "
                + "a fight. Whoever travels is the one who waits: the asker for /tpa, and the person "
                + "who answered for /tpahere. Zero sends them at once.")
        @Key("tpa.warmup-seconds")
        int warmupSeconds,

        @In("tpa/travelling") @Title("Moving cancels the wait")
        @Describe("Whether leaving the block you started on gives up on the teleport. Turning and "
                + "looking around never counts — only leaving the block.")
        @Key("tpa.cancel-on-move")
        boolean cancelOnMove,

        @In("tpa/travelling") @Title("Being hurt cancels the wait")
        @Describe("Whether taking damage gives up on the teleport.")
        @Key("tpa.cancel-on-damage")
        boolean cancelOnDamage,

        @In("tpa/asking") @Title("Wait between requests") @Range(min = 0, max = 3600)
        @Describe("Seconds before the same player may ask again. Keeps one person from asking the "
                + "whole server in a row. Zero switches it off.")
        @Key("tpa.cooldown-seconds")
        int cooldownSeconds,

        @In("tpa/travelling") @Title("Requests may cross worlds")
        @Describe("Whether somebody may ask a player in another world, and whether going back may "
                + "cross one.")
        @Key("tpa.allow-cross-world")
        boolean allowCrossWorld,

        @In("tpa/travelling") @Title("Operators skip the waits")
        @Describe("Whether being an operator counts as holding the bypass permissions for the "
                + "standing still and both waits, without being granted them. Off by default, and "
                + "deliberately: an admin who silently bypasses a feature is the one person who "
                + "cannot test it. Note it never covers being able to ask somebody who has requests "
                + "switched off — that one is permission-only.")
        @Key("tpa.operators-bypass")
        boolean operatorsBypass,

        @In("tpa/back") @Title("Going back is switched on")
        @Describe("Whether /back exists at all. Off removes the command and its button together — a "
                + "menu that offers something the server does not have is worse than one that does "
                + "not offer it.")
        @Key("tpa.back-enabled")
        boolean backEnabled,

        @In("tpa/back") @Title("Dying is somewhere to go back to")
        @Describe("Whether /back takes somebody to where they died. A death outranks a teleport "
                + "until it is used, so being moved afterwards does not lose the spot their things "
                + "are lying on.")
        @Key("tpa.back-on-death")
        boolean backOnDeath,

        @In("tpa/back") @Title("Wait between going back") @Range(min = 0, max = 3600)
        @Describe("Seconds before /back may be used again. Zero switches it off.")
        @Key("tpa.back-cooldown-seconds")
        int backCooldownSeconds,

        @In("tpa/asking") @Title("A bell when somebody asks")
        @Describe("Whether the person being asked hears a sound. Off leaves them the chat line "
                + "alone — which is still clickable, and still there when they come back to the "
                + "keyboard.")
        @Key("tpa.notify-sound")
        boolean notifySound) {

    /** Exactly what the old plugin shipped: {@code (60, 3, true, true, 5, true, false, true, true, 10, true)}. */
    public static final TpaSettings DEFAULTS =
            new TpaSettings(60, 3, true, true, 5, true, false, true, true, 10, true);

    // ------------------------------------------------------------------ read back safely

    /**
     * How long a request stands, clamped.
     *
     * <p>The store clamps what it reads from the file, but a {@code TpaSettings} can also be built in
     * code — by a test, or by a host handing in its own — and a request that stands for zero seconds
     * is one nobody can ever answer.
     */
    public int requestStanding() {
        return Math.max(5, Math.min(600, requestSeconds));
    }

    public int warmup() {
        return Math.max(0, Math.min(60, warmupSeconds));
    }

    public int cooldown() {
        return Math.max(0, Math.min(3600, cooldownSeconds));
    }

    public int backCooldown() {
        return Math.max(0, Math.min(3600, backCooldownSeconds));
    }

    // ------------------------------------------------------------------ one component at a time

    public TpaSettings withRequestSeconds(int seconds) {
        return new TpaSettings(seconds, warmupSeconds, cancelOnMove, cancelOnDamage, cooldownSeconds,
                allowCrossWorld, operatorsBypass, backEnabled, backOnDeath, backCooldownSeconds,
                notifySound);
    }

    public TpaSettings withWarmupSeconds(int seconds) {
        return new TpaSettings(requestSeconds, seconds, cancelOnMove, cancelOnDamage, cooldownSeconds,
                allowCrossWorld, operatorsBypass, backEnabled, backOnDeath, backCooldownSeconds,
                notifySound);
    }

    public TpaSettings withCancelOnMove(boolean cancels) {
        return new TpaSettings(requestSeconds, warmupSeconds, cancels, cancelOnDamage,
                cooldownSeconds, allowCrossWorld, operatorsBypass, backEnabled, backOnDeath,
                backCooldownSeconds, notifySound);
    }

    public TpaSettings withCancelOnDamage(boolean cancels) {
        return new TpaSettings(requestSeconds, warmupSeconds, cancelOnMove, cancels, cooldownSeconds,
                allowCrossWorld, operatorsBypass, backEnabled, backOnDeath, backCooldownSeconds,
                notifySound);
    }

    public TpaSettings withCooldownSeconds(int seconds) {
        return new TpaSettings(requestSeconds, warmupSeconds, cancelOnMove, cancelOnDamage, seconds,
                allowCrossWorld, operatorsBypass, backEnabled, backOnDeath, backCooldownSeconds,
                notifySound);
    }

    public TpaSettings withAllowCrossWorld(boolean allow) {
        return new TpaSettings(requestSeconds, warmupSeconds, cancelOnMove, cancelOnDamage,
                cooldownSeconds, allow, operatorsBypass, backEnabled, backOnDeath,
                backCooldownSeconds, notifySound);
    }

    public TpaSettings withOperatorsBypass(boolean bypass) {
        return new TpaSettings(requestSeconds, warmupSeconds, cancelOnMove, cancelOnDamage,
                cooldownSeconds, allowCrossWorld, bypass, backEnabled, backOnDeath,
                backCooldownSeconds, notifySound);
    }

    public TpaSettings withBackEnabled(boolean enabled) {
        return new TpaSettings(requestSeconds, warmupSeconds, cancelOnMove, cancelOnDamage,
                cooldownSeconds, allowCrossWorld, operatorsBypass, enabled, backOnDeath,
                backCooldownSeconds, notifySound);
    }

    public TpaSettings withBackOnDeath(boolean onDeath) {
        return new TpaSettings(requestSeconds, warmupSeconds, cancelOnMove, cancelOnDamage,
                cooldownSeconds, allowCrossWorld, operatorsBypass, backEnabled, onDeath,
                backCooldownSeconds, notifySound);
    }

    public TpaSettings withBackCooldownSeconds(int seconds) {
        return new TpaSettings(requestSeconds, warmupSeconds, cancelOnMove, cancelOnDamage,
                cooldownSeconds, allowCrossWorld, operatorsBypass, backEnabled, backOnDeath, seconds,
                notifySound);
    }

    public TpaSettings withNotifySound(boolean sound) {
        return new TpaSettings(requestSeconds, warmupSeconds, cancelOnMove, cancelOnDamage,
                cooldownSeconds, allowCrossWorld, operatorsBypass, backEnabled, backOnDeath,
                backCooldownSeconds, sound);
    }
}
