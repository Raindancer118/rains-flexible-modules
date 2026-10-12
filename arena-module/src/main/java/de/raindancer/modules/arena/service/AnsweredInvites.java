package de.raindancer.modules.arena.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Invites already said yes to, kept for a minute — so a second click on [Accept] (players click twice when a pit is
 * not free at once) is told it went through, not that the invite vanished.
 */
public final class AnsweredInvites {

    private static final long KEPT = 60_000;

    private record Answer(UUID player, long until) {
    }

    private final Map<String, Answer> answers = new ConcurrentHashMap<>();

    public void remember(String id, UUID player, long now) {
        answers.values().removeIf(answer -> answer.until() < now);
        answers.put(id.toLowerCase(java.util.Locale.ROOT), new Answer(player, now + KEPT));
    }

    public boolean answeredBy(String id, UUID player, long now) {
        Answer answer = answers.get(id.toLowerCase(java.util.Locale.ROOT));
        return answer != null && answer.player().equals(player) && answer.until() >= now;
    }
}
