package de.raindancer.modules.tpa;

import de.raindancer.modules.tpa.model.TpaKind;
import de.raindancer.modules.tpa.model.TpaRequest;
import de.raindancer.modules.tpa.store.TpaRequests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who has asked whom, and what a second asking means.
 *
 * <h2>The two rules that are not symmetrical, and why</h2>
 * <b>One outgoing request per player; incoming is uncapped.</b> Asking is something you do and being
 * asked is something that happens to you — capping the second would mean one person could stop
 * everybody else reaching somebody by asking first. And a second request to a <em>different</em> person
 * displaces the first, because somebody who has changed their mind about where they are going has said
 * so; but a second request to the <em>same</em> person is refused outright, because that is not a
 * change of mind, it is asking twice.
 *
 * <h2>Why expiry is asked rather than swept</h2>
 * A request that has run out is expired the moment it is looked at, whatever any timer has or has not
 * done. A sweep that has not fired yet must never let somebody accept a request that lapsed — which is
 * exactly the race the old plugin had, and it took a real server and a pair of bots to find.
 */
class TpaRequestsTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());
    private static final UUID CAROL = UUID.nameUUIDFromBytes("carol".getBytes());

    /** Milliseconds, moved by hand — a test that waits for a minute is a test nobody runs. */
    private final AtomicLong now = new AtomicLong(1_000);

    private TpaRequests requests;

    @BeforeEach
    void setUp() {
        requests = new TpaRequests(now::get);
        requests.standingFor(Duration.ofSeconds(60));
    }

    private TpaRequest asking(UUID from, UUID to) {
        return requests.put(from, to, TpaKind.TO).orElseThrow();
    }

    @Nested
    @DisplayName("asking")
    class Asking {

        @Test
        @DisplayName("a request is remembered, and both sides can find it")
        void itIsRemembered() {
            TpaRequest made = asking(ALICE, BOB);

            assertThat(made.from()).isEqualTo(ALICE);
            assertThat(made.to()).isEqualTo(BOB);
            assertThat(requests.from(ALICE)).contains(made);
            assertThat(requests.to(BOB)).containsExactly(made);
        }

        @Test
        @DisplayName("which way round it goes is kept")
        void theDirectionIsKept() {
            // /tpa and /tpahere are one request with two answers to "who travels", and getting that
            // backwards teleports the wrong person across the world.
            requests.put(ALICE, BOB, TpaKind.HERE);

            assertThat(requests.from(ALICE).orElseThrow().kind()).isEqualTo(TpaKind.HERE);
        }

        @Test
        @DisplayName("asking the same person twice is refused")
        void askingTwiceIsRefused() {
            asking(ALICE, BOB);

            assertThat(requests.put(ALICE, BOB, TpaKind.TO))
                    .as("that is not a change of mind, it is asking twice — and the answer they have "
                            + "not given yet is still coming")
                    .isEmpty();
        }

        @Test
        @DisplayName("asking somebody else instead displaces the first")
        void askingElsewhereDisplaces() {
            asking(ALICE, BOB);

            Optional<TpaRequest> made = requests.put(ALICE, CAROL, TpaKind.TO);

            assertThat(made).isPresent();
            assertThat(requests.to(BOB))
                    .as("Bob's is gone, and the caller has to be able to tell him so")
                    .isEmpty();
            assertThat(requests.to(CAROL)).hasSize(1);
        }

        @Test
        @DisplayName("the displaced request is handed back, so the person can be told")
        void theDisplacedOneIsReturned() {
            TpaRequest first = asking(ALICE, BOB);

            assertThat(requests.displacedBy(ALICE, CAROL, TpaKind.TO))
                    .as("a request that vanishes without the asked person being told is one they go "
                            + "on waiting to answer")
                    .contains(first);
        }

        @Test
        @DisplayName("being asked by several people at once is fine")
        void incomingIsUncapped() {
            // Deliberately not capped. Being asked is not something the person being asked did, and a
            // cap would let one person block everybody else by asking first.
            requests.put(ALICE, CAROL, TpaKind.TO);
            requests.put(BOB, CAROL, TpaKind.TO);

            assertThat(requests.to(CAROL)).hasSize(2);
        }

        @Test
        @DisplayName("nobody cannot ask, and cannot be asked")
        void nullIsRefused() {
            assertThat(requests.put(null, BOB, TpaKind.TO)).isEmpty();
            assertThat(requests.put(ALICE, null, TpaKind.TO)).isEmpty();
            assertThat(requests.put(ALICE, BOB, null)).isEmpty();
            assertThat(requests.count()).isZero();
        }

        @Test
        @DisplayName("nobody may ask themselves")
        void askingYourselfIsRefused() {
            assertThat(requests.put(ALICE, ALICE, TpaKind.TO)).isEmpty();
        }
    }

    @Nested
    @DisplayName("answering")
    class Answering {

        @Test
        @DisplayName("taking a request by name gives it and forgets it")
        void takingByName() {
            TpaRequest made = asking(ALICE, BOB);

            assertThat(requests.take(BOB, ALICE)).contains(made);
            assertThat(requests.to(BOB))
                    .as("answered twice is teleported twice")
                    .isEmpty();
        }

        @Test
        @DisplayName("two requests in the same millisecond are still ordered the same way twice")
        void tiesAreBrokenDeterministically() {
            // Both made at the same instant. A comparator that called them equal would leave the order
            // to whatever the map iterated, so bare /tpaccept would take an arbitrary one of the two —
            // and not necessarily the one at the top of the menu the player is looking at.
            requests.put(ALICE, CAROL, TpaKind.TO);
            requests.put(BOB, CAROL, TpaKind.TO);

            assertThat(requests.to(CAROL)).isEqualTo(requests.to(CAROL));
            assertThat(requests.to(CAROL).getFirst())
                    .as("and the one taken is the one shown first")
                    .isEqualTo(requests.take(CAROL, null).orElseThrow());
        }

        @Test
        @DisplayName("taking with nobody named gives the newest")
        void takingTheNewest() {
            // What bare /tpaccept means. The newest rather than the oldest: the one that just
            // appeared on screen is the one somebody is answering.
            requests.put(ALICE, CAROL, TpaKind.TO);
            now.addAndGet(1_000);
            TpaRequest newer = requests.put(BOB, CAROL, TpaKind.TO).orElseThrow();

            assertThat(requests.take(CAROL, null)).contains(newer);
        }

        @Test
        @DisplayName("only the person asked may take it")
        void onlyTheAskedMayTake() {
            asking(ALICE, BOB);

            assertThat(requests.take(CAROL, ALICE))
                    .as("otherwise anybody could accept a request meant for somebody else and be "
                            + "teleported in their place")
                    .isEmpty();
            assertThat(requests.to(BOB)).hasSize(1);
        }

        @Test
        @DisplayName("taking one that was never made is empty")
        void takingNothing() {
            assertThat(requests.take(BOB, ALICE)).isEmpty();
            assertThat(requests.take(BOB, null)).isEmpty();
            assertThat(requests.take(null, null)).isEmpty();
        }

        @Test
        @DisplayName("the asker can take their own request back")
        void withdrawing() {
            TpaRequest made = asking(ALICE, BOB);

            assertThat(requests.withdraw(ALICE)).contains(made);
            assertThat(requests.to(BOB)).isEmpty();
        }
    }

    @Nested
    @DisplayName("running out")
    class Expiring {

        @Test
        @DisplayName("a request that has run out is not there to be answered")
        void expiredIsGone() {
            asking(ALICE, BOB);

            now.addAndGet(Duration.ofSeconds(60).toMillis());

            assertThat(requests.to(BOB))
                    .as("expired the moment it is looked at, whatever a timer has or has not done — "
                            + "a sweep that has not fired yet must never let somebody accept a "
                            + "request that lapsed")
                    .isEmpty();
            assertThat(requests.take(BOB, ALICE)).isEmpty();
        }

        @Test
        @DisplayName("one millisecond before, it still stands")
        void theEdgeIsExact() {
            asking(ALICE, BOB);

            now.addAndGet(Duration.ofSeconds(60).toMillis() - 1);

            assertThat(requests.to(BOB)).hasSize(1);
        }

        @Test
        @DisplayName("sweeping hands back what lapsed, so both sides can be told")
        void sweepingReports() {
            TpaRequest made = asking(ALICE, BOB);
            requests.put(CAROL, BOB, TpaKind.TO);

            now.addAndGet(Duration.ofSeconds(60).toMillis());
            List<TpaRequest> lapsed = requests.expire();

            assertThat(lapsed).hasSize(2).contains(made);
            assertThat(requests.count()).isZero();
        }

        @Test
        @DisplayName("sweeping leaves what has not run out")
        void sweepingKeepsTheLiveOnes() {
            asking(ALICE, BOB);
            now.addAndGet(30_000);
            requests.put(CAROL, BOB, TpaKind.TO);

            now.addAndGet(30_000);

            assertThat(requests.expire()).hasSize(1);
            assertThat(requests.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("how long is left, for the line that says so")
        void itSaysHowLongIsLeft() {
            asking(ALICE, BOB);

            now.addAndGet(20_000);

            assertThat(requests.from(ALICE).orElseThrow().secondsLeft(now.get())).isEqualTo(40);
        }

        @Test
        @DisplayName("a request displaced before it ran out is not reported as lapsed too")
        void displacedIsNotAlsoLapsed() {
            // Both would tell the same person their request ended, twice, for two different reasons.
            asking(ALICE, BOB);
            requests.put(ALICE, CAROL, TpaKind.TO);

            now.addAndGet(Duration.ofSeconds(60).toMillis());

            assertThat(requests.expire())
                    .as("only the one that was actually still standing")
                    .hasSize(1);
        }
    }

    @Nested
    @DisplayName("letting go")
    class Forgetting {

        @Test
        @DisplayName("somebody who logged out has their request taken back")
        void quittingWithdraws() {
            asking(ALICE, BOB);

            assertThat(requests.forget(ALICE)).hasSize(1);
            assertThat(requests.to(BOB)).isEmpty();
        }

        @Test
        @DisplayName("and every request made to them goes too")
        void quittingClearsIncoming() {
            // Otherwise the people who asked go on waiting for an answer from somebody who is not
            // there, until it runs out on its own.
            requests.put(ALICE, CAROL, TpaKind.TO);
            requests.put(BOB, CAROL, TpaKind.TO);

            assertThat(requests.forget(CAROL)).hasSize(2);
            assertThat(requests.count()).isZero();
        }

        @Test
        @DisplayName("a request that had just expired is still reported when they leave")
        void anExpiredRequestIsStillReported() {
            // The other side is standing there holding a request that is now gone either way, and
            // "they left" is the true and useful thing to say. Saying nothing leaves them waiting to
            // answer something that no longer exists.
            asking(ALICE, BOB);
            now.addAndGet(Duration.ofSeconds(60).toMillis());

            assertThat(requests.forget(ALICE))
                    .as("expired or not, it was theirs and it has ended")
                    .hasSize(1);
        }

        @Test
        @DisplayName("somebody else's live request is left alone")
        void otherPeoplesRequestsSurvive() {
            asking(ALICE, BOB);
            requests.put(CAROL, BOB, TpaKind.TO);

            assertThat(requests.forget(ALICE)).hasSize(1);
            assertThat(requests.from(CAROL))
                    .as("Carol asked Bob, and Alice logging out has nothing to do with it")
                    .isPresent();
        }

        @Test
        @DisplayName("everything can be dropped at once")
        void everythingCanBeDropped() {
            asking(ALICE, BOB);
            requests.put(CAROL, BOB, TpaKind.TO);

            requests.clear();

            assertThat(requests.count()).isZero();
        }
    }

    @Nested
    @DisplayName("asked by several threads at once")
    class Concurrently {

        private static final int RACERS = 64;
        private static final int ROUNDS = 200;

        private int race(java.util.function.IntSupplier oneRacer) throws Exception {
            CountDownLatch ready = new CountDownLatch(RACERS);
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger yeses = new AtomicInteger();

            try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int racer = 0; racer < RACERS; racer++) {
                    threads.submit(() -> {
                        ready.countDown();
                        try {
                            go.await();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        yeses.addAndGet(oneRacer.getAsInt());
                    });
                }
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                go.countDown();
                threads.shutdown();
                assertThat(threads.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
            }
            return yeses.get();
        }

        @Test
        @DisplayName("only one of many simultaneous answers takes the request")
        void onlyOneAccepts() throws Exception {
            // The one that matters. Two threads both taking the same request is one teleport promised
            // to two people — and on Folia a command and a click really are two threads.
            for (int round = 0; round < ROUNDS; round++) {
                requests.clear();
                requests.put(ALICE, BOB, TpaKind.TO);

                int taken = race(() -> requests.take(BOB, null).isPresent() ? 1 : 0);

                assertThat(taken)
                        .as("round %d: %d threads all took the same request", round, taken)
                        .isEqualTo(1);
            }
        }

        @Test
        @DisplayName("simultaneous asks leave exactly one outgoing request")
        void onlyOneOutgoingSurvives() throws Exception {
            for (int round = 0; round < ROUNDS; round++) {
                requests.clear();

                race(() -> requests.put(ALICE, BOB, TpaKind.TO).isPresent() ? 1 : 0);

                assertThat(requests.from(ALICE))
                        .as("round %d: one player, one outgoing request, whatever the timing", round)
                        .isPresent();
                assertThat(requests.count()).isEqualTo(1);
            }
        }
    }
}
