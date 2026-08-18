package de.raindancer.modules.chat;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;

/**
 * What an owner can decide about public chat: how a line is laid out, whether @-mentions ping
 * anybody, the quality rules a message has to pass before it goes out at all, and how much of it
 * {@code /chathistory} remembers.
 *
 * <h2>What is deliberately not here</h2>
 * A player's prefix, suffix and name colour are Core's own {@code Identities} — set once there, read
 * by the tablist, the nametag and this module's own {@link de.raindancer.modules.chat.service.FormatService}
 * alike. Reintroducing them as a second, chat-only copy is exactly the drift {@code Identities}
 * exists to prevent. What this settles is only the shape <em>around</em> that name: the template it
 * sits in, and whether the message beside it is allowed through.
 */
@Settings(id = "chat", topics = {
        @Topic(path = "chat/format", title = "Format", icon = Material.WRITABLE_BOOK),
        @Topic(path = "chat/mentions", title = "Mentions", icon = Material.BELL),
        @Topic(path = "chat/quality", title = "Message quality", icon = Material.HOPPER),
        @Topic(path = "chat/history", title = "History", icon = Material.CLOCK),
})
public record ChatSettings(

        @In("chat/format") @Title("Chat format")
        @Describe("How a line is laid out. <name> is the player's coloured name with their prefix "
                + "and suffix; <message> is what they typed. The default colour, the name colour and "
                + "the brackets around the name are their own settings below, applied to whatever "
                + "this template places <name> and <message> in — this is only for changing the "
                + "layout itself, a different separator or order.")
        String format,

        @In("chat/format") @Title("Click a name to message them")
        @Describe("Whether clicking somebody's name in chat fills in /msg <name> — harmless on a "
                + "server without essentials-module's /msg, since nothing runs until it is sent.")
        boolean clickToMessage,

        @In("chat/format") @Title("Colour links")
        @Describe("Whether a web address typed in chat is coloured and made clickable, rather than "
                + "shown as plain text.")
        boolean linkifyUrls,

        @In("chat/format") @Title("Default message colour")
        @Describe("The colour a message renders in when its sender has not picked their own with "
                + "/chatstyle — never overrides a colour they did pick.")
        NamedTextColor defaultMessageColor,

        @In("chat/format") @Title("Default name colour")
        @Describe("The colour a player's name renders in when nothing else has already coloured it "
                + "— a nickname colour set elsewhere always wins over this.")
        NamedTextColor defaultNameColor,

        @In("chat/format") @Title("Brackets around the name")
        @Describe("Wraps the name in <angle brackets>, the way vanilla chat always has, on top of "
                + "whatever this module's own format template already does with <name>.")
        boolean bracketsAroundName,

        @In("chat/mentions") @Title("@-mentions")
        @Describe("Whether typing @name in chat pings whoever is online and answers to it — a "
                + "sound and a message, so it is not missed in a busy chat.")
        boolean mentionsEnabled,

        @In("chat/quality") @Title("Block SHOUTING")
        @Describe("Refuses a message that is mostly capital letters, once it is long enough for "
                + "that to mean something.")
        boolean capsFilterEnabled,

        @In("chat/quality") @Title("Shouting threshold") @Range(min = 50, max = 100)
        @Describe("The percentage of letters that have to be capitals before a message counts as "
                + "shouting.")
        int capsThresholdPercent,

        @In("chat/quality") @Title("Shortest message the caps filter checks") @Range(min = 4, max = 40)
        @Describe("A message shorter than this is never refused for shouting — \"NO\" is not "
                + "somebody yelling.")
        int capsMinLength,

        @In("chat/quality") @Title("Block repeating a message")
        @Describe("Refuses sending the exact same message twice in a row.")
        boolean repeatBlockEnabled,

        @In("chat/quality") @Title("Seconds between messages") @Range(min = 0, max = 30)
        @Describe("How long somebody has to wait after one message before the next is accepted. "
                + "Zero switches this off.")
        int messageCooldownSeconds,

        @In("chat/quality") @Title("Default slowmode") @Range(min = 0, max = 60)
        @Describe("A cooldown applied to everybody, all the time, on top of the one above — meant "
                + "to be raised with /chat slowmode during a busy moment rather than left high. "
                + "Zero switches it off.")
        int defaultSlowmodeSeconds,

        @In("chat/history") @Title("/chathistory")
        @Describe("Whether this module remembers recent chat at all, for /chathistory to answer "
                + "\"what did I miss\" with.")
        boolean historyEnabled,

        @In("chat/history") @Title("Lines kept") @Range(min = 20, max = 1000)
        @Describe("How many of the most recent chat lines are kept — server-wide, not per player. "
                + "The oldest is dropped once a newer line pushes past this.")
        int historyCapacity,

        @In("chat/history") @Title("Say so on join")
        @Describe("Whether somebody is told they missed messages the moment they join, rather than "
                + "only finding out by typing /chathistory.")
        boolean historyNotifyOnJoin

) {

    public static final ChatSettings DEFAULTS = new ChatSettings(
            "<name>: <message>", true, true, NamedTextColor.WHITE, NamedTextColor.WHITE, false,
            true, true, 70, 8, true, 0, 0, true, 200, true);

    /** Clamped, so a hand-built settings record cannot ask for an impossible threshold. */
    public int capsThreshold() {
        return Math.max(50, Math.min(100, capsThresholdPercent));
    }

    /** Clamped, so a hand-built settings record cannot make the caps filter fire on "hi". */
    public int capsMinimumLength() {
        return Math.max(4, Math.min(40, capsMinLength));
    }

    /** Clamped, so a hand-built settings record cannot ask for a negative wait. */
    public int messageCooldown() {
        return Math.max(0, Math.min(30, messageCooldownSeconds));
    }

    /** Clamped, so a hand-built settings record cannot ask for a negative slowmode. */
    public int defaultSlowmode() {
        return Math.max(0, Math.min(60, defaultSlowmodeSeconds));
    }

    /** Clamped, so a hand-built settings record cannot ask for a capacity of zero or less. */
    public int historyLimit() {
        return Math.max(20, Math.min(1000, historyCapacity));
    }
}
