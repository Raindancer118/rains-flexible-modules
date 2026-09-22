package de.raindancer.modules.chat.service;

import de.raindancer.modules.chat.ChatSettings;
import de.raindancer.modules.chat.model.PrivateChat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
 * <h2>Being added switches you over, but walking out is final</h2>
 * The owner adding somebody puts them straight into the chat and makes it where their next line goes
 * — that was the point of asking for it. The price of that is that anybody could be pulled into a
 * chat they did not want, over and over. So somebody who <em>leaves</em> may not be added back to the
 * same chat; a fresh chat starts with a fresh list. Being removed by the owner, or merely
 * disconnecting, does not count — neither was their own decision.
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
        ADDED,
        ALREADY_A_MEMBER,
        IN_ANOTHER_CHAT,
        LEFT_THIS_CHAT,
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
        final Set<UUID> walkedOut = new HashSet<>();

        Room(UUID owner) {
            this.owner = owner;
            members.add(owner);
        }

        PrivateChat snapshot() {
            return new PrivateChat(owner, members);
        }
    }

    private final Object lock = new Object();
    private final Map<UUID, Room> roomOf = new HashMap<>();
    private final Set<UUID> talking = new HashSet<>();

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
     * {@code owner} adding {@code target}, starting {@code owner}'s chat first if they had none — adding
     * somebody is as clear a way of asking for a private chat as {@code /chat private} itself.
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
            if (room != null && room.walkedOut.contains(target)) {
                return Outcome.LEFT_THIS_CHAT;
            }
            if (room == null) {
                room = new Room(owner);
                roomOf.put(owner, room);
                talking.add(owner);
            }
            room.members.add(target);
            roomOf.put(target, room);
            talking.add(target);
            return Outcome.ADDED;
        }
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
     * Somebody walking out of their chat for good — see the class note on why they cannot be dragged
     * back. The owner walking out ends it for everybody: a chat nobody may add to or end is a room
     * with the door welded shut.
     */
    public Outcome leave(UUID who) {
        return departing(who, true);
    }

    /** Somebody going offline. The same as {@link #leave}, except that they may be added again. */
    public Outcome disconnect(UUID who) {
        return departing(who, false);
    }

    private Outcome departing(UUID who, boolean byChoice) {
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
            if (byChoice) {
                room.walkedOut.add(who);
            }
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
    }

    @Override
    public String describe() {
        return "/chat private: who is in which private chat, and whose lines go there";
    }
}
