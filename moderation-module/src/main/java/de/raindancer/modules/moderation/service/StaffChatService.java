package de.raindancer.modules.moderation.service;

import de.raindancer.modules.moderation.ModerationSettings;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is talking in the staff channel rather than to the server.
 *
 * <h2>Why a toggle rather than a prefix</h2>
 * Because the failure of the prefix approach is asymmetric and expensive: somebody who forgets the
 * {@code #} has said to the whole server what they meant to say to two people. A toggle fails the other
 * way — somebody who forgets they are in staff chat says nothing to anybody, notices, and says it
 * again. {@code /staffchat <message>} still works for one line without touching the toggle.
 *
 * <h2>Thread safety</h2>
 * The whole point of this class. Chat events are asynchronous, so "is this person in staff chat" is
 * always read from a different thread from the one the toggle command ran on. A plain {@code HashSet}
 * here is a race on every message.
 */
public final class StaffChatService implements IModerationService {

    private final Set<UUID> talking = ConcurrentHashMap.newKeySet();

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
        if (talking.remove(who)) {
            return false;
        }
        talking.add(who);
        return true;
    }

    public boolean isTalking(UUID who) {
        return who != null && talking.contains(who);
    }

    /** Puts somebody back into ordinary chat. @return whether they were in staff chat */
    public boolean stop(UUID who) {
        return who != null && talking.remove(who);
    }

    /** Everybody currently talking to the staff, as a snapshot. */
    public Set<UUID> everybodyTalking() {
        return Collections.unmodifiableSet(Set.copyOf(talking));
    }

    /**
     * Forgets somebody who has left.
     *
     * <p>Without this the set grows by an entry per person who has ever used staff chat, and somebody
     * who logs back in is silently still in it — saying to two people what they think they are saying
     * to the server.
     */
    public void forget(UUID who) {
        if (who != null) {
            talking.remove(who);
        }
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
