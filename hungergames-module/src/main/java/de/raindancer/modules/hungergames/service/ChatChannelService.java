package de.raindancer.modules.hungergames.service;

import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.model.ChatChannel;
import de.raindancer.modules.hungergames.store.GameSession;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which of a tribute's own two channels — {@link ChatChannel#TEAM} or {@link ChatChannel#ALL} — their
 * next chat line goes to, and the one override neither they nor a gamemaster can undo.
 *
 * <h2>The bug this exists to fix</h2>
 * There was no channel at all: every tribute's chat was ordinary server chat, on for everybody including
 * whoever had just been eliminated. A team coordinating a fight was overheard by the sixteen people they
 * were fighting, and once somebody was out, nothing changed for them — they kept talking in the same room
 * as the round they had just left.
 *
 * <h2>Why elimination is asked fresh every time, not stored</h2>
 * {@link #effectiveChannel} reads {@link GameSession#participants()} on every call rather than reacting to
 * an elimination event. A stored flag can only be as current as the last event that updated it, and this
 * module has already had one bug where a fact computed once went stale the moment the state it was drawn
 * from changed under it — see {@code ArenaBuildService}'s history with {@code GamePhase}. Asking fresh
 * costs one map lookup and cannot disagree with the roster.
 *
 * <h2>Why the preference survives being eliminated</h2>
 * A tribute who was in {@link ChatChannel#TEAM} and gets eliminated is <em>not</em> switched to
 * {@code ALL} in the map — {@link #effectiveChannel} simply overrides it to {@link ChatChannel#SPECTATOR}
 * for as long as they are out. If a round were ever undone by an admin's revive, they land back exactly
 * where they left off rather than in whatever the default happens to be.
 */
public final class ChatChannelService implements IHungerGamesService {

    /** Whether asking for a channel worked, and why not when it did not. */
    public enum SwitchOutcome {
        OK,
        YOU_ARE_A_SPECTATOR,
        YOU_HAVE_NO_TEAM
    }

    private final GameSession session;
    private final Map<UUID, ChatChannel> preferred = new ConcurrentHashMap<>();

    public ChatChannelService(GameSession session) {
        this.session = session;
    }

    /** Nothing here reads a setting — see {@link IHungerGamesService}'s note on implementing this empty. */
    @Override
    public void settings(HungerGamesSettings settings) {
        // intentionally empty
    }

    /**
     * Where this tribute's next chat line actually goes — never {@code null}, and never a channel they
     * could not currently use.
     */
    public ChatChannel effectiveChannel(UUID tribute) {
        if (!session.participants().isAlive(tribute)) {
            return ChatChannel.SPECTATOR;
        }
        ChatChannel chosen = preferred.getOrDefault(tribute, ChatChannel.ALL);
        if (chosen == ChatChannel.TEAM && session.teams().teamOf(tribute).isEmpty()) {
            // Remembered for the day they join one, but unusable until then — see the class note on why
            // this is not simply rewritten to ALL: a preference nobody asked to change should still read
            // back as what they chose, once TeamsMenu lets them join a team.
            return ChatChannel.ALL;
        }
        return chosen;
    }

    /**
     * A tribute asking to switch. Refuses outright rather than half-applying — see {@link SwitchOutcome}.
     */
    public SwitchOutcome switchTo(UUID tribute, ChatChannel requested) {
        if (requested == ChatChannel.SPECTATOR) {
            // Not a door anybody opens on purpose — see the class javadoc. Treated the same as being a
            // spectator already, since the two refusals mean the same thing to whoever typed the command.
            return SwitchOutcome.YOU_ARE_A_SPECTATOR;
        }
        if (!session.participants().isAlive(tribute)) {
            return SwitchOutcome.YOU_ARE_A_SPECTATOR;
        }
        if (requested == ChatChannel.TEAM && session.teams().teamOf(tribute).isEmpty()) {
            return SwitchOutcome.YOU_HAVE_NO_TEAM;
        }
        preferred.put(tribute, requested);
        return SwitchOutcome.OK;
    }

    /** The other of the two switchable channels — what "just toggle it" means. */
    public ChatChannel other(UUID tribute) {
        return effectiveChannel(tribute) == ChatChannel.TEAM ? ChatChannel.ALL : ChatChannel.TEAM;
    }

    /** Drops a remembered preference — a fresh tribute, or one who has disconnected, starts at the default. */
    public void forget(UUID tribute) {
        preferred.remove(tribute);
    }

    @Override
    public String describe() {
        return "which channel a tribute's chat goes to: their team, everybody, or fellow spectators";
    }
}
