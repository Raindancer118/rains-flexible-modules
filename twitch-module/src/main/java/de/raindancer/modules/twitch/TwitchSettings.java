package de.raindancer.modules.twitch;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.Material;

import java.time.Duration;

/**
 * What an owner can decide about the live announcements. The Twitch login (client id and secret) is deliberately not
 * here: settings are shown in menus, a secret is not.
 */
@Settings(id = "twitch", topics = {
        @Topic(path = "twitch", title = "Twitch announcements", icon = Material.AMETHYST_SHARD),
})
public record TwitchSettings(

        @In("twitch") @Title("Announcements are on")
        @Describe("Whether going live and the end of a stream are told in chat. Off silences this server, "
                + "and it stops asking Twitch.")
        @Key("enabled")
        boolean enabled,

        @In("twitch") @Title("Channel")
        @Describe("The Twitch channel to watch, as in twitch.tv/<channel>. Only the server that has the Twitch "
                + "login in credentials.yml asks Twitch; the others are told over the network link.")
        @Key("channel")
        String channel,

        @In("twitch") @Title("Look every") @Range(min = 30, max = 600)
        @Describe("Seconds between looks at Twitch. Read when the server starts.")
        @Key("poll-seconds")
        int pollSeconds,

        @In("twitch") @Title("Over after this many looks") @Range(min = 1, max = 10)
        @Describe("How many looks in a row must find the channel offline before the end is announced — a stream "
                + "that drops for a minute and comes back is not over.")
        @Key("end-after-looks")
        int endAfterLooks,

        @In("twitch") @Title("Only announce streams that began within") @Range(min = 1, max = 720)
        @Describe("Minutes. A server that starts while a stream has been running for hours stays quiet about it.")
        @Key("fresh-minutes")
        int freshMinutes,

        @In("twitch") @Title("Play a sound")
        @Describe("A short chime with the announcement.")
        @Key("sound")
        boolean sound) {

    public static final TwitchSettings DEFAULTS = new TwitchSettings(true, "lillyyxoxo_", 60, 3, 15, true);

    public String channelClean() {
        String clean = channel == null ? "" : channel.strip().toLowerCase(java.util.Locale.ROOT);
        return clean.matches("[a-z0-9_]{1,25}") ? clean : DEFAULTS.channel;
    }

    public String url() {
        return "https://www.twitch.tv/" + channelClean();
    }

    public Duration fresh() {
        return Duration.ofMinutes(Math.clamp(freshMinutes, 1, 720));
    }

    public int pollSecondsClamped() {
        return Math.clamp(pollSeconds, 30, 600);
    }
}
