package de.raindancer.modules.sends;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.Material;

/**
 * What an owner can decide about sends.
 *
 * <p>The record <em>is</em> the schema: the file, its comments, its validation and the {@code /settings}
 * screens all come from it.
 */
@Settings(id = "sends", topics = {
        @Topic(path = "sends", title = "Sends", icon = Material.CHEST_MINECART),
})
public record SendsSettings(

        @In("sends") @Title("A send waits for") @Range(min = 1, max = 1440)
        @Describe("Minutes the other player has to accept. Past that the items go back to whoever sent "
                + "them. Sends survive a restart, and the clock keeps running while the server is down.")
        @Key("expiry-minutes")
        int expiryMinutes,

        @In("sends") @Title("Sends one player may have waiting") @Range(min = 1, max = 100)
        @Describe("How many sends a player can have out at once that nobody has accepted yet. Each holds "
                + "up to 27 stacks, so this is also the most they can have parked here.")
        @Key("most-waiting")
        int mostWaiting,

        @In("sends") @Title("Wait between sends") @Range(min = 0, max = 3600)
        @Describe("Seconds before the same player may make another send. Zero switches the wait off.")
        @Key("cooldown-seconds")
        int cooldownSeconds,

        @In("sends") @Title("Remind players when they join")
        @Describe("Tell a player at login that sends are waiting for them, and give back what has come "
                + "back to them while they were away.")
        @Key("remind-on-join")
        boolean remindOnJoin) {

    public static final SendsSettings DEFAULTS = new SendsSettings(10, 5, 5, true);

    public long lifetimeMillis() {
        return Math.max(1, expiryMinutes) * 60_000L;
    }

    public int mostWaitingClamped() {
        return Math.max(1, mostWaiting);
    }

    public SendsSettings withExpiryMinutes(int value) {
        return new SendsSettings(value, mostWaiting, cooldownSeconds, remindOnJoin);
    }

    public SendsSettings withMostWaiting(int value) {
        return new SendsSettings(expiryMinutes, value, cooldownSeconds, remindOnJoin);
    }

    public SendsSettings withCooldownSeconds(int value) {
        return new SendsSettings(expiryMinutes, mostWaiting, value, remindOnJoin);
    }

    public SendsSettings withRemindOnJoin(boolean value) {
        return new SendsSettings(expiryMinutes, mostWaiting, cooldownSeconds, value);
    }
}
