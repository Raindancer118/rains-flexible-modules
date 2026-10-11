package de.raindancer.modules.sends.store;

import de.raindancer.modules.sends.model.Send;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Every send somebody still has a claim on, and the only place its state changes.
 *
 * <h2>Why every change is one method under one lock</h2>
 * Accepting, cancelling and running out are three ways of ending the same wait, and exactly one may win. Each
 * of them checks the state and changes it in a single {@code synchronized} step, so two clicks — or a click and
 * the expiry sweep — cannot both hand the same items to somebody. What the winner then does with the items is
 * the caller's business and happens after this returns.
 *
 * <h2>Why the change and the write are two steps</h2>
 * Memory changes at once and the disk follows on another thread, because a database write on the thread that
 * runs the world is a stall. The caller therefore hands items over only after {@link #persist} has answered
 * true: a crash in between leaves the send pending on disk and the items still here, never given twice. When the
 * write fails the caller calls {@link #revert} and nothing has happened.
 */
public final class SendBook {

    public enum Outcome { DONE, NOT_FOUND, NOT_YOURS, WRONG_STATE, EXPIRED, NO_ROOM }

    /**
     * @param send   the send after the change (the one to persist); the unchanged one when nothing changed
     * @param before the send as it was, for {@link #revert}
     */
    public record Result(Outcome outcome, Send send, Send before) {

        public boolean done() {
            return outcome == Outcome.DONE;
        }

        static Result refused(Outcome outcome, Send send) {
            return new Result(outcome, send, send);
        }
    }

    private final Map<UUID, Send> open = new LinkedHashMap<>();
    /** Made but not yet on disk: nobody can see, accept or cancel these, because they may yet not exist. */
    private final Map<UUID, Send> saving = new LinkedHashMap<>();
    /**
     * Sends whose last change is not on disk yet. Nothing else may change them until it is: a change that is
     * undone after a failed write must not undo something somebody else did to the send in the meantime — a
     * collected send put back to pending would be given twice.
     */
    private final java.util.Set<UUID> locked = new java.util.HashSet<>();
    private final SendStorage storage;
    private final Executor writer;
    private final LongSupplier clock;

    /** @param writer where writes run: one thread, so a send's writes land in the order they were asked for */
    public SendBook(SendStorage storage, Executor writer, LongSupplier clock) {
        this.storage = storage;
        this.writer = writer;
        this.clock = clock;
    }

    public synchronized void load() {
        for (Send send : storage.loadOpen()) {
            open.put(send.id(), send);
        }
    }

    /**
     * Takes a new send in, invisible: it counts towards its sender's limit but cannot be claimed, released or
     * listed until {@link #publish} — which is only called once the write has succeeded. Visible earlier, a
     * recipient could accept it in the moment before a failing write, and the items would be given to them and
     * back to the sender both.
     */
    public synchronized void add(Send send) {
        saving.put(send.id(), send);
    }

    /** The first write succeeded: the send now exists for everybody. */
    public synchronized boolean publish(UUID id) {
        Send send = saving.remove(id);
        if (send == null) {
            return false;
        }
        open.put(id, send);
        return true;
    }

    public CompletableFuture<Boolean> persist(Send send) {
        try {
            return CompletableFuture.supplyAsync(() -> storage.save(send), writer)
                    .exceptionally(failed -> false);
        } catch (java.util.concurrent.RejectedExecutionException closed) {
            return CompletableFuture.completedFuture(false);
        }
    }

    // ------------------------------------------------------------------------------ changing

    /** Pending to delivered — the recipient takes it, if it has not run out and {@code room} says it fits. */
    public synchronized Result claim(UUID id, UUID recipient, Predicate<Send> room) {
        Send send = open.get(id);
        if (send == null) {
            return new Result(Outcome.NOT_FOUND, null, null);
        }
        if (!send.recipient().equals(recipient)) {
            return Result.refused(Outcome.NOT_YOURS, send);
        }
        if (send.state() != Send.State.PENDING || locked.contains(id)) {
            return Result.refused(Outcome.WRONG_STATE, send);
        }
        long now = clock.getAsLong();
        if (send.isExpiredAt(now)) {
            return Result.refused(Outcome.EXPIRED, send);
        }
        if (!room.test(send)) {
            return Result.refused(Outcome.NO_ROOM, send);
        }
        Send delivered = send.withState(Send.State.DELIVERED, now);
        open.remove(id);
        return new Result(Outcome.DONE, delivered, send);
    }

    /** Pending to returning — the sender cancels, or the recipient declines. */
    public synchronized Result release(UUID id, UUID by) {
        Send send = open.get(id);
        if (send == null) {
            return new Result(Outcome.NOT_FOUND, null, null);
        }
        if (!send.sender().equals(by) && !send.recipient().equals(by)) {
            return Result.refused(Outcome.NOT_YOURS, send);
        }
        if (send.state() != Send.State.PENDING || locked.contains(id)) {
            return Result.refused(Outcome.WRONG_STATE, send);
        }
        Send returning = send.withState(Send.State.RETURNING, clock.getAsLong());
        open.put(id, returning);
        locked.add(id);
        return new Result(Outcome.DONE, returning, send);
    }

    /** Returning to returned — the sender takes their items back, if {@code room} says they fit. */
    public synchronized Result collect(UUID id, UUID sender, Predicate<Send> room) {
        Send send = open.get(id);
        if (send == null) {
            return new Result(Outcome.NOT_FOUND, null, null);
        }
        if (!send.sender().equals(sender)) {
            return Result.refused(Outcome.NOT_YOURS, send);
        }
        if (send.state() != Send.State.RETURNING || locked.contains(id)) {
            return Result.refused(Outcome.WRONG_STATE, send);
        }
        if (!room.test(send)) {
            return Result.refused(Outcome.NO_ROOM, send);
        }
        Send returned = send.withState(Send.State.RETURNED, clock.getAsLong());
        open.remove(id);
        return new Result(Outcome.DONE, returned, send);
    }

    /** Moves every pending send that has run out to returning, and says which. */
    public synchronized List<Send> expire() {
        long now = clock.getAsLong();
        List<Send> ran = new ArrayList<>();
        for (Send send : List.copyOf(open.values())) {
            if (send.isExpiredAt(now)) {
                Send returning = send.withState(Send.State.RETURNING, now);
                open.put(send.id(), returning);
                locked.add(send.id());
                ran.add(returning);
            }
        }
        return ran;
    }

    /** Takes a send out altogether — one whose first write failed, so it never existed on disk. */
    public synchronized void discard(UUID id) {
        saving.remove(id);
        open.remove(id);
        locked.remove(id);
    }

    /** Puts a send back as it was, after a change that could not be written or delivered. */
    public synchronized void revert(Send before) {
        open.put(before.id(), before);
        locked.remove(before.id());
    }

    /** The last change of this send is on disk (or given up on): it may change again. */
    public synchronized void unlock(UUID id) {
        locked.remove(id);
    }

    // ------------------------------------------------------------------------------ asking

    public synchronized List<Send> incoming(UUID recipient) {
        return ofState(Send.State.PENDING, send -> send.recipient().equals(recipient));
    }

    /** How many sends this player has out: those waiting to be accepted and those still being saved. */
    public synchronized int outgoingCount(UUID sender) {
        return outgoing(sender).size()
                + (int) saving.values().stream().filter(send -> send.sender().equals(sender)).count();
    }

    public synchronized List<Send> outgoing(UUID sender) {
        return ofState(Send.State.PENDING, send -> send.sender().equals(sender));
    }

    public synchronized List<Send> waitingReturn(UUID sender) {
        return ofState(Send.State.RETURNING, send -> send.sender().equals(sender));
    }

    /** One of this player's open sends, by its full id or the first characters of it; none if that is ambiguous. */
    public synchronized Optional<Send> find(String typed, UUID who) {
        if (typed == null || typed.isBlank()) {
            return Optional.empty();
        }
        String wanted = typed.trim().toLowerCase(java.util.Locale.ROOT);
        List<Send> matches = open.values().stream()
                .filter(send -> send.sender().equals(who) || send.recipient().equals(who))
                .filter(send -> send.id().toString().startsWith(wanted))
                .toList();
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    public synchronized Optional<Send> byId(UUID id) {
        return Optional.ofNullable(open.get(id));
    }

    private List<Send> ofState(Send.State state, Predicate<Send> filter) {
        return open.values().stream()
                .filter(send -> send.state() == state)
                .filter(filter)
                .sorted(Comparator.comparingLong(Send::createdAt))
                .toList();
    }
}
