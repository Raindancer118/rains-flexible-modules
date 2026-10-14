package de.raindancer.modules.sends.store;

import de.raindancer.modules.sends.model.Send;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SendBookTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final UUID CAROL = UUID.randomUUID();
    private static final long MINUTE = 60_000;

    private MemorySendStorage storage;
    private AtomicLong now;
    private SendBook book;

    @BeforeEach
    void setUp() {
        storage = new MemorySendStorage();
        now = new AtomicLong(1_000_000);
        book = new SendBook(storage, Runnable::run, now::get);
    }

    private ItemStack item() {
        ItemStack item = mock(ItemStack.class);
        when(item.getAmount()).thenReturn(1);
        return item;
    }

    private Send made() {
        ItemStack item = mock(ItemStack.class);
        when(item.getAmount()).thenReturn(1);
        Send send = Send.create(ALICE, "Alice", BOB, "Bob", List.of(item), now.get(), 10 * MINUTE);
        book.add(send);
        book.publish(send.id());
        return send;
    }

    @Test
    @DisplayName("a send still being saved cannot be seen, accepted or cancelled, but counts towards the limit")
    void invisibleUntilPublished() {
        ItemStack item = mock(ItemStack.class);
        when(item.getAmount()).thenReturn(1);
        Send send = Send.create(ALICE, "Alice", BOB, "Bob", List.of(item), now.get(), 10 * MINUTE);
        book.add(send);
        assertThat(book.incoming(BOB)).isEmpty();
        assertThat(book.claim(send.id(), BOB, any -> true).outcome()).isEqualTo(SendBook.Outcome.NOT_FOUND);
        assertThat(book.release(send.id(), ALICE).outcome()).isEqualTo(SendBook.Outcome.NOT_FOUND);
        assertThat(book.outgoingCount(ALICE)).isEqualTo(1);
        now.addAndGet(60 * MINUTE);
        assertThat(book.expire()).isEmpty();

        assertThat(book.publish(send.id())).isTrue();
        assertThat(book.publish(send.id())).as("only once").isFalse();
        assertThat(book.incoming(BOB)).hasSize(1);
        assertThat(book.outgoingCount(ALICE)).isEqualTo(1);
    }

    @Test
    @DisplayName("discarding a send that was never saved leaves nothing, and frees the limit")
    void discardFreesTheLimit() {
        ItemStack item = mock(ItemStack.class);
        when(item.getAmount()).thenReturn(1);
        Send send = Send.create(ALICE, "Alice", BOB, "Bob", List.of(item), now.get(), 10 * MINUTE);
        book.add(send);
        book.discard(send.id());
        assertThat(book.outgoingCount(ALICE)).isZero();
        assertThat(book.publish(send.id())).isFalse();
    }

    @Test
    @DisplayName("the recipient sees what is waiting for them, oldest first, and nobody else does")
    void incoming() {
        Send first = made();
        now.addAndGet(1000);
        Send second = made();
        assertThat(book.incoming(BOB)).extracting(Send::id).containsExactly(first.id(), second.id());
        assertThat(book.incoming(CAROL)).isEmpty();
        assertThat(book.outgoing(ALICE)).hasSize(2);
        assertThat(book.outgoing(BOB)).isEmpty();
    }

    @Test
    @DisplayName("claiming hands the send over exactly once")
    void claimOnce() {
        Send send = made();
        SendBook.Result first = book.claim(send.id(), BOB, any -> true);
        assertThat(first.outcome()).isEqualTo(SendBook.Outcome.DONE);
        assertThat(first.send().state()).isEqualTo(Send.State.DELIVERED);
        assertThat(book.claim(send.id(), BOB, any -> true).outcome()).isEqualTo(SendBook.Outcome.NOT_FOUND);
        assertThat(book.incoming(BOB)).isEmpty();
    }

    @Test
    @DisplayName("only the recipient can claim it")
    void claimIsTheRecipientsAlone() {
        Send send = made();
        assertThat(book.claim(send.id(), CAROL, any -> true).outcome()).isEqualTo(SendBook.Outcome.NOT_YOURS);
        assertThat(book.claim(send.id(), ALICE, any -> true).outcome()).isEqualTo(SendBook.Outcome.NOT_YOURS);
        assertThat(book.incoming(BOB)).hasSize(1);
    }

    @Test
    @DisplayName("no room leaves the send where it was, still claimable")
    void noRoom() {
        Send send = made();
        assertThat(book.claim(send.id(), BOB, any -> false).outcome()).isEqualTo(SendBook.Outcome.NO_ROOM);
        assertThat(book.incoming(BOB)).hasSize(1);
        assertThat(book.claim(send.id(), BOB, any -> true).outcome()).isEqualTo(SendBook.Outcome.DONE);
    }

    @Test
    @DisplayName("a send is not claimable at or after its expiry, even before the sweep has run")
    void expiredIsNotClaimable() {
        Send send = made();
        now.addAndGet(10 * MINUTE);
        assertThat(book.claim(send.id(), BOB, any -> true).outcome()).isEqualTo(SendBook.Outcome.EXPIRED);
    }

    @Test
    @DisplayName("expiring moves what ran out to the sender, once")
    void expireOnce() {
        Send send = made();
        assertThat(book.expire()).isEmpty();
        now.addAndGet(10 * MINUTE);
        assertThat(book.expire()).extracting(Send::id).containsExactly(send.id());
        assertThat(book.expire()).isEmpty();
        assertThat(book.incoming(BOB)).isEmpty();
        assertThat(book.waitingReturn(ALICE)).extracting(Send::id).containsExactly(send.id());
    }

    @Test
    @DisplayName("the sender can cancel, the recipient can decline, a stranger can do neither")
    void release() {
        Send one = made();
        Send two = made();
        Send three = made();
        assertThat(book.release(one.id(), ALICE).outcome()).isEqualTo(SendBook.Outcome.DONE);
        assertThat(book.release(two.id(), BOB).outcome()).isEqualTo(SendBook.Outcome.DONE);
        assertThat(book.release(three.id(), CAROL).outcome()).isEqualTo(SendBook.Outcome.NOT_YOURS);
        assertThat(book.waitingReturn(ALICE)).hasSize(2);
        assertThat(book.release(one.id(), ALICE).outcome()).isEqualTo(SendBook.Outcome.WRONG_STATE);
    }

    @Test
    @DisplayName("a cancelled send can no longer be accepted")
    void cancelledCannotBeAccepted() {
        Send send = made();
        book.release(send.id(), ALICE);
        assertThat(book.claim(send.id(), BOB, any -> true).outcome()).isEqualTo(SendBook.Outcome.WRONG_STATE);
    }

    @Test
    @DisplayName("the sender collects a returned send once, and only when it fits")
    void collect() {
        Send send = made();
        book.release(send.id(), ALICE);
        book.unlock(send.id());
        assertThat(book.collect(send.id(), BOB, any -> true).outcome()).isEqualTo(SendBook.Outcome.NOT_YOURS);
        assertThat(book.collect(send.id(), ALICE, any -> false).outcome()).isEqualTo(SendBook.Outcome.NO_ROOM);
        SendBook.Result done = book.collect(send.id(), ALICE, any -> true);
        assertThat(done.outcome()).isEqualTo(SendBook.Outcome.DONE);
        assertThat(done.send().state()).isEqualTo(Send.State.RETURNED);
        assertThat(book.collect(send.id(), ALICE, any -> true).outcome()).isEqualTo(SendBook.Outcome.NOT_FOUND);
    }

    @Test
    @DisplayName("a send whose change is not on disk yet cannot be changed again — a failed write must not undo it later")
    void lockedUntilWritten() {
        Send send = made();
        SendBook.Result released = book.release(send.id(), ALICE);
        assertThat(book.collect(send.id(), ALICE, any -> true).outcome()).isEqualTo(SendBook.Outcome.WRONG_STATE);
        assertThat(book.release(send.id(), BOB).outcome()).isEqualTo(SendBook.Outcome.WRONG_STATE);
        book.unlock(send.id());
        assertThat(book.collect(send.id(), ALICE, any -> true).outcome()).isEqualTo(SendBook.Outcome.DONE);

        Send second = made();
        SendBook.Result again = book.release(second.id(), ALICE);
        book.revert(again.before());
        assertThat(book.claim(second.id(), BOB, any -> true).outcome()).as("a revert unlocks").isEqualTo(SendBook.Outcome.DONE);
        assertThat(released.done()).isTrue();
    }

    @Test
    @DisplayName("what the sweep moved is locked until its write is done")
    void sweepLocks() {
        Send send = made();
        now.addAndGet(10 * MINUTE);
        book.expire();
        assertThat(book.collect(send.id(), ALICE, any -> true).outcome()).isEqualTo(SendBook.Outcome.WRONG_STATE);
        book.unlock(send.id());
        assertThat(book.collect(send.id(), ALICE, any -> true).outcome()).isEqualTo(SendBook.Outcome.DONE);
    }

    @Test
    @DisplayName("a pending send cannot be collected as if it had been returned")
    void pendingIsNotCollectable() {
        Send send = made();
        assertThat(book.collect(send.id(), ALICE, any -> true).outcome()).isEqualTo(SendBook.Outcome.WRONG_STATE);
    }

    @Test
    @DisplayName("reverting puts a send back the way it was")
    void revert() {
        Send send = made();
        SendBook.Result claimed = book.claim(send.id(), BOB, any -> true);
        book.revert(claimed.before());
        assertThat(book.incoming(BOB)).extracting(Send::id).containsExactly(send.id());
        assertThat(book.claim(send.id(), BOB, any -> true).outcome()).isEqualTo(SendBook.Outcome.DONE);
    }

    @Test
    @DisplayName("persisting says whether it was written; a move is made once, and a move that was already made is lost")
    void persists() {
        Send send = made();
        assertThat(book.persist(send).join()).isTrue();
        SendBook.Result released = book.release(send.id(), ALICE);
        assertThat(book.persistChange(released.before(), released.send()).join()).isEqualTo(SendStorage.Written.DONE);
        assertThat(storage.saved.get(send.id()).state()).isEqualTo(Send.State.RETURNING);
        assertThat(book.persistChange(released.before(), released.send()).join())
                .as("the same move again finds the send already moved").isEqualTo(SendStorage.Written.LOST);
        storage.failing = true;
        assertThat(book.persist(released.send()).join()).isFalse();
        assertThat(book.persistChange(released.send(), released.send()).join()).isEqualTo(SendStorage.Written.FAILED);
    }

    @Test
    @DisplayName("what was open when the server stopped is open again when it starts")
    void loadsOpen() {
        Send open = made();
        Send settled = made();
        book.persist(open).join();
        book.persist(settled).join();
        SendBook.Result claimed = book.claim(settled.id(), BOB, any -> true);
        book.persistChange(claimed.before(), claimed.send()).join();

        SendBook restarted = new SendBook(storage, Runnable::run, now::get);
        restarted.load();
        assertThat(restarted.incoming(BOB)).extracting(Send::id).containsExactly(open.id());
    }

    @Test
    @DisplayName("a send another server made is learnt by reloading it, and one it settled is forgotten")
    void reloadFollowsTheDatabase() {
        Send elsewhere = Send.create(CAROL, "Carol", BOB, "Bob", List.of(item()), now.get(), 10 * MINUTE);
        storage.save(elsewhere);

        assertThat(book.reload(elsewhere.id())).contains(elsewhere);
        assertThat(book.incoming(BOB)).extracting(Send::id).containsExactly(elsewhere.id());
        assertThat(book.reload(elsewhere.id())).as("nothing changed the second time").isEmpty();

        storage.change(elsewhere, elsewhere.withState(Send.State.DELIVERED, now.get()));
        book.reload(elsewhere.id());
        assertThat(book.incoming(BOB)).isEmpty();
    }

    @Test
    @DisplayName("a reload does not undo a change of this server's that is still being written")
    void reloadLeavesChangesInFlightAlone() {
        Send send = made();
        storage.save(send);
        book.release(send.id(), ALICE);
        book.reload(send.id());
        assertThat(book.waitingReturn(ALICE)).extracting(Send::id).containsExactly(send.id());
    }

    @Test
    @DisplayName("a database that cannot be read leaves what is known")
    void unreadableLeavesWhatIsKnown() {
        Send send = made();
        SendStorage broken = new MemorySendStorage() {
            @Override
            public synchronized Optional<Optional<Send>> load(UUID id) {
                return Optional.empty();
            }
        };
        SendBook blind = new SendBook(broken, Runnable::run, now::get);
        blind.add(send);
        blind.publish(send.id());
        blind.reload(send.id());
        assertThat(blind.incoming(BOB)).hasSize(1);
    }

    @Test
    @DisplayName("a send that ran out while the server was down is returned at the first sweep")
    void expiredWhileDown() {
        Send send = made();
        book.persist(send).join();
        now.addAndGet(60 * MINUTE);
        SendBook restarted = new SendBook(storage, Runnable::run, now::get);
        restarted.load();
        assertThat(restarted.expire()).extracting(Send::id).containsExactly(send.id());
    }

    @Test
    @DisplayName("an id typed in full or as its first characters finds the send, ambiguity finds nothing")
    void findsByPrefix() {
        Send send = made();
        assertThat(book.find(send.shortId(), BOB)).contains(send);
        assertThat(book.find(send.id().toString(), BOB)).contains(send);
        assertThat(book.find("zzzz", BOB)).isEmpty();
        assertThat(book.find(send.shortId(), CAROL)).isEmpty();
        assertThat(book.find("", BOB)).isEmpty();
    }
}
