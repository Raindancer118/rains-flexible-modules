package de.raindancer.modules.manhunt.service;

import de.raindancer.modules.manhunt.ManhuntSettings;

import java.util.Objects;
import java.util.Optional;

/**
 * Who hears what a participant types while a hunt is running.
 *
 * <h2>Why a plain rule rather than a channel system</h2>
 * A Manhunt has exactly two sides and lasts an hour. Channels, a current channel per player and a
 * command to switch between them would be four moving parts to say one thing: while the hunt is on,
 * your side hears you. The one exception worth having is a way to say something to the whole server
 * without leaving the hunt — a prefix, which is a rule and not a mode, so nobody can get stuck in the
 * wrong one and nothing has to be reset when the hunt ends.
 *
 * <h2>Bukkit-free, like every decision in this module</h2>
 * {@code ManhuntChatListener} converts an {@code AsyncChatEvent} into a question and filters the
 * event's viewers by the answer — see {@link ManhuntLobbyBox} for the same split and the reasoning
 * behind it.
 */
public final class SideChat {

    /** Who a message reaches. */
    public enum Audience {
        /** Everybody on the server, exactly as it would have been without this module. */
        EVERYBODY,
        /** Only the speaker's own side. */
        OWN_SIDE
    }

    /**
     * Which channel a line is shown as — the tag in front of it.
     *
     * <p>Asked for directly, after {@code moderation-module}'s staff chat: every staff line carries
     * its channel's tag, so nobody reading chat has to guess where a line went. Side chat had no
     * mark at all — the same line everybody always sees, only to fewer people — so a Runner could not
     * tell whether they had just told their team or the whole server, which in a hunt is the one
     * mistake that matters.
     */
    public enum Channel {
        /** Not this module's line at all: no hunt, a bystander, or side chat switched off. */
        NONE,
        /** Heard by the Runners only. */
        RUNNERS,
        /** Heard by the Hunters only. */
        HUNTERS,
        /** A participant speaking to the whole server mid-hunt, through the prefix. */
        EVERYBODY
    }

    private volatile ManhuntSettings settings;

    public SideChat(ManhuntSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /** Told the live settings whenever they change — wired via {@code SettingsStore.onChange}. */
    public void settings(ManhuntSettings fresh) {
        this.settings = fresh;
    }

    /**
     * Who should hear {@code message}.
     *
     * @param huntRunning whether a hunt is going at all — outside one, this module has no business
     *                    touching anybody's chat
     * @param onASide     whether the speaker is a Runner or a Hunter; a bystander is never narrowed
     */
    public Audience audienceFor(String message, boolean huntRunning, boolean onASide) {
        ManhuntSettings config = settings;
        if (!huntRunning || !onASide || !config.sideChat()) {
            return Audience.EVERYBODY;
        }
        String prefix = config.sideChatGlobalPrefix();
        if (prefix != null && !prefix.isEmpty() && message != null && message.startsWith(prefix)) {
            return Audience.EVERYBODY;
        }
        return Audience.OWN_SIDE;
    }

    /**
     * The channel {@code message} is shown as. Agrees with {@link #audienceFor} by construction —
     * the side channels are exactly the lines narrowed to a side.
     */
    public Channel channelFor(String message, boolean huntRunning, boolean runner, boolean hunter) {
        boolean onASide = runner || hunter;
        if (!huntRunning || !onASide || !settings.sideChat()) {
            return Channel.NONE;
        }
        if (audienceFor(message, true, true) == Audience.EVERYBODY) {
            return Channel.EVERYBODY;
        }
        return runner ? Channel.RUNNERS : Channel.HUNTERS;
    }

    /**
     * The message with its global prefix taken off, when it had one. Nobody wants to read the
     * punctuation that routed the line, and leaving it in would also let it be typed twice to no
     * effect.
     */
    public String strip(String message) {
        String prefix = settings.sideChatGlobalPrefix();
        if (prefix == null || prefix.isEmpty() || message == null || !message.startsWith(prefix)) {
            return message;
        }
        return message.substring(prefix.length()).stripLeading();
    }

    /** Whether side chat is switched on at all. */
    public boolean enabled() {
        return settings.sideChat();
    }

    /** The prefix a player would have to type to be heard by everybody, if there is one at all. */
    public Optional<String> globalPrefix() {
        String prefix = settings.sideChatGlobalPrefix();
        return prefix == null || prefix.isEmpty() ? Optional.empty() : Optional.of(prefix);
    }
}
