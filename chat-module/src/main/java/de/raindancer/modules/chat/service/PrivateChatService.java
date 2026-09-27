package de.raindancer.modules.chat.service;

import de.raindancer.modules.chat.ChatSettings;
import de.raindancer.modules.chat.model.PrivateChat;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * {@code /chat private} — who is in which private chat, and whose next line goes there rather than to
 * the server.
 *
 * <h2>Being in a chat and talking in it are two different things</h2>
 * A member who types {@code /chat public} stays in the chat and keeps reading it; only their own lines
 * go back to the server. That is what makes "switch back at any time" cheap: nobody has to be added
 * again to rejoin a conversation they only stepped out of to answer somebody in public.
 * {@code /chat private leave} is the way out altogether.
 *
 * <h2>Nobody is pulled in: an invitation has to be accepted</h2>
 * The owner {@link #invite invites}; the person joins only by {@link #accept accepting}, and is then
 * switched over at once. An invitation is good for one answer and runs out after
 * {@link #INVITE_STANDS}, and a second one to the same person while the first stands is refused — the
 * only way left to pester somebody. Because joining needs a yes, somebody who left may be invited back.
 *
 * <h2>One chat each</h2>
 * Somebody already in one private chat cannot be added to a second. Two would mean deciding which of
 * them a line goes to, and a wrong guess there is a private line said in the wrong room.
 *
 * <h2>Thread safety</h2>
 * Asked from the async chat thread on every line and changed from commands on another, so every method
 * holds the one lock. None of them does more than a few map operations, and nothing here is ever
 * handed out except as a {@link PrivateChat} snapshot.
 *
 * <h2>Nothing is written to disk</h2>
 * A private chat lives as long as its owner is online. A server that came back from a restart with
 * somebody still silently talking into a room nobody else is in would lose their messages without
 * anybody noticing.
 */
public final class PrivateChatService implements IChatService {

    /** What asking for something did, and why not when it did not. */
    public enum Outcome {
        STARTED,
        SWITCHED,
        ALREADY_PRIVATE,
        INVITED,
        ALREADY_INVITED,
        INVITE_GONE,
        DECLINED,
        ADDED,
        ALREADY_A_MEMBER,
        IN_ANOTHER_CHAT,
        NOT_YOURSELF,
        NOT_THE_OWNER,
        NOT_A_MEMBER,
        NOT_IN_A_CHAT,
        REMOVED,
        LEFT,
        ENDED
    }

    /** A chat's live state. Only ever touched under the lock. */
    private static final class Room {
        final UUID owner;
        final Set<UUID> members = new LinkedHashSet<>();
        /** Who may still accept, and until when (epoch millis). */
        final Map<UUID, Long> invited = new HashMap<>();

        Room(UUID owner) {
            this.owner = owner;
            members.add(owner);
        }

        PrivateChat snapshot() {
            return new PrivateChat(owner, members);
        }
    }

    /** How long an invitation can be accepted. */
    public static final Duration INVITE_STANDS = Duration.ofSeconds(60);

    private final LongSupplier clock;
    private final Object lock = new Object();
    private final Map<UUID, Room> roomOf = new HashMap<>();
    private final Set<UUID> talking = new HashSet<>();

    public PrivateChatService() {
        this(System::currentTimeMillis);
    }

    PrivateChatService(LongSupplier clock) {
        this.clock = clock;
    }

    @Override
    public void settings(ChatSettings settings) {
        // Nothing here reads settings; see IChatService on why this is implemented anyway.
    }

    /** Where {@code who}'s next line goes: their chat if they have one, a new one of their own if not. */
    public Outcome goPrivate(UUID who) {
        synchronized (lock) {
            if (roomOf.containsKey(who)) {
                return talking.add(who) ? Outcome.SWITCHED : Outcome.ALREADY_PRIVATE;
            }
            roomOf.put(who, new Room(who));
            talking.add(who);
            return Outcome.STARTED;
        }
    }

    /** @return whether this changed anything — they stay a member either way */
    public boolean goPublic(UUID who) {
        synchronized (lock) {
            return talking.remove(who);
        }
    }

    /**
     * {@code owner} inviting {@code target}, starting {@code owner}'s chat first if they had none —
     * inviting somebody is as clear a way of asking for a private chat as {@code /chat private} itself.
     */
    public Outcome invite(UUID owner, UUID target) {
        synchronized (lock) {
            if (owner.equals(target)) {
                return Outcome.NOT_YOURSELF;
            }
            Room room = roomOf.get(owner);
            if (room != null && !room.owner.equals(owner)) {
                return Outcome.NOT_THE_OWNER;
            }
            Room theirs = roomOf.get(target);
            if (theirs != null) {
                return theirs == room ? Outcome.ALREADY_A_MEMBER : Outcome.IN_ANOTHER_CHAT;
            }
            long now = clock.getAsLong();
            if (room != null && standing(room.invited.get(target), now)) {
                return Outcome.ALREADY_INVITED;
            }
            if (room == null) {
                room = open(owner);
            }
            room.invited.put(target, now + INVITE_STANDS.toMillis());
            return Outcome.INVITED;
        }
    }

    /** {@code target} saying yes to {@code owner}'s invitation — the only way into somebody's chat. */
    public Outcome accept(UUID target, UUID owner) {
        synchronized (lock) {
            Room room = roomOf.get(owner);
            if (room == null || !room.owner.equals(owner)
                    || !standing(room.invited.remove(target), clock.getAsLong())) {
                return Outcome.INVITE_GONE;
            }
            Room theirs = roomOf.get(target);
            if (theirs != null) {
                return theirs == room ? Outcome.ALREADY_A_MEMBER : Outcome.IN_ANOTHER_CHAT;
            }
            join(room, target);
            return Outcome.ADDED;
        }
    }

    public Outcome decline(UUID target, UUID owner) {
        synchronized (lock) {
            Room room = roomOf.get(owner);
            boolean was = room != null && room.owner.equals(owner)
                    && standing(room.invited.remove(target), clock.getAsLong());
            return was ? Outcome.DECLINED : Outcome.INVITE_GONE;
        }
    }

    /**
     * Puts {@code target} straight in without asking. Not reachable from any command — the way in for
     * a player is {@link #accept}; this is for callers that already have their consent.
     */
    public Outcome add(UUID owner, UUID target) {
        synchronized (lock) {
            if (owner.equals(target)) {
                return Outcome.NOT_YOURSELF;
            }
            Room room = roomOf.get(owner);
            if (room != null && !room.owner.equals(owner)) {
                return Outcome.NOT_THE_OWNER;
            }
            Room theirs = roomOf.get(target);
            if (theirs != null) {
                return theirs == room ? Outcome.ALREADY_A_MEMBER : Outcome.IN_ANOTHER_CHAT;
            }
            join(room == null ? open(owner) : room, target);
            return Outcome.ADDED;
        }
    }

    private Room open(UUID owner) {
        Room room = new Room(owner);
        roomOf.put(owner, room);
        talking.add(owner);
        return room;
    }

    private void join(Room room, UUID target) {
        room.invited.remove(target);
        room.members.add(target);
        roomOf.put(target, room);
        talking.add(target);
    }

    private static boolean standing(Long until, long now) {
        return until != null && until >= now;
    }

    /** The owner taking somebody out. They may be added again later — it was not their decision. */
    public Outcome remove(UUID owner, UUID target) {
        synchronized (lock) {
            Room room = roomOf.get(owner);
            if (room == null) {
                return Outcome.NOT_IN_A_CHAT;
            }
            if (!room.owner.equals(owner)) {
                return Outcome.NOT_THE_OWNER;
            }
            if (owner.equals(target)) {
                return Outcome.NOT_YOURSELF;
            }
            if (roomOf.get(target) != room) {
                return Outcome.NOT_A_MEMBER;
            }
            takeOut(room, target);
            return Outcome.REMOVED;
        }
    }

    /**
     * Somebody walking out of their chat. The owner walking out ends it for everybody: a chat nobody may add to or end is a room
     * with the door welded shut.
     */
    public Outcome leave(UUID who) {
        return departing(who);
    }

    /** Somebody going offline. */
    public Outcome disconnect(UUID who) {
        return departing(who);
    }

    private Outcome departing(UUID who) {
        synchronized (lock) {
            Room room = roomOf.get(who);
            if (room == null) {
                talking.remove(who);
                return Outcome.NOT_IN_A_CHAT;
            }
            if (room.owner.equals(who)) {
                dissolve(room);
                return Outcome.ENDED;
            }
            takeOut(room, who);
            return Outcome.LEFT;
        }
    }

    /**
     * Ends {@code owner}'s chat, putting everybody in it back into public chat.
     *
     * @return everybody who was in it, to be told — empty if {@code owner} owns no chat
     */
    public Optional<PrivateChat> end(UUID owner) {
        synchronized (lock) {
            Room room = roomOf.get(owner);
            if (room == null || !room.owner.equals(owner)) {
                return Optional.empty();
            }
            PrivateChat ended = room.snapshot();
            dissolve(room);
            return Optional.of(ended);
        }
    }

    public boolean isTalkingPrivately(UUID who) {
        synchronized (lock) {
            return talking.contains(who);
        }
    }

    public Optional<PrivateChat> chatOf(UUID who) {
        synchronized (lock) {
            Room room = roomOf.get(who);
            return room == null ? Optional.empty() : Optional.of(room.snapshot());
        }
    }

    /** Everybody who reads a line {@code speaker} says in private, themselves included. */
    public Set<UUID> readersOf(UUID speaker) {
        synchronized (lock) {
            Room room = roomOf.get(speaker);
            return room == null ? Set.of() : Set.copyOf(room.members);
        }
    }

    private void takeOut(Room room, UUID who) {
        room.members.remove(who);
        roomOf.remove(who);
        talking.remove(who);
    }

    private void dissolve(Room room) {
        for (UUID member : room.members) {
            roomOf.remove(member);
            talking.remove(member);
        }
        room.members.clear();
        room.invited.clear();
    }

    @Override
    public String describe() {
        return "/chat private: who is in which private chat, and whose lines go there";
    }
}
