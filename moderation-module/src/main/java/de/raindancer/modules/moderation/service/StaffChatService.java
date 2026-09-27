package de.raindancer.modules.moderation.service;

import de.raindancer.modules.moderation.ModerationSettings;

import de.raindancer.core.ui.chat.ChatChannels;
import de.raindancer.modules.moderation.util.StaffChannel;

import java.util.UUID;

/**
 * Who is talking in the staff channel rather than to the server.
 *
 * <h2>Why a toggle rather than a prefix</h2>
 * Because the failure of the prefix approach is asymmetric and expensive: somebody who forgets the
 * {@code #} has said to the whole server what they meant to say to two people. A toggle fails the other
 * way — somebody who forgets they are in staff chat says nothing to anybody, notices, and says it
 * again. {@code /staffchat <message>} still works for one line without touching the toggle.
 *
 * <h2>Where the toggle lives</h2>
 * In Core's {@code ChatChannels}, as the channel {@code staff} — the same selection {@code /chat staff}
 * and RainsChat's picker change, so there is one answer to "where does my next line go". It is a
 * concurrent map, which is what an async chat thread reading it needs.
 */
public final class StaffChatService implements IModerationService {

    private volatile ModerationSettings settings = ModerationSettings.DEFAULTS;

    public StaffChatService() {
    }

    public StaffChatService(ModerationSettings settings) {
        settings(settings);
    }

    /** @return whether they are now talking in the staff channel */
    public boolean toggle(UUID who) {
        if (who == null) {
            return false;
        }
        if (isTalking(who)) {
            ChatChannels.select(who, ChatChannels.ALL);
            return false;
        }
        ChatChannels.select(who, StaffChannel.ID);
        return true;
    }

    public boolean isTalking(UUID who) {
        return who != null && StaffChannel.ID.equals(ChatChannels.selected(who));
    }

    /** Puts somebody back into ordinary chat. @return whether they were in staff chat */
    public boolean stop(UUID who) {
        if (!isTalking(who)) {
            return false;
        }
        ChatChannels.select(who, ChatChannels.ALL);
        return true;
    }

    /**
     * Forgets somebody who has left.
     *
     * <p>Without this somebody who logs back in is silently still in it — saying to two people what
     * they think they are saying to the server. Any other channel they picked is left to its owner.
     */
    public void forget(UUID who) {
        stop(who);
    }

    /** What marks the channel. Read from the settings so a reload changes it. */
    public String prefix() {
        return settings.staffChatPrefix();
    }

    @Override
    public void settings(ModerationSettings settings) {
        this.settings = settings == null ? ModerationSettings.DEFAULTS : settings;
    }

    @Override
    public String describe() {
        return "the staff channel: who is talking in it rather than to the server";
    }
}
