package de.raindancer.modules.tpa;

import de.raindancer.modules.tpa.model.TpaKind;
import de.raindancer.modules.tpa.model.TpaRequest;
import de.raindancer.modules.tpa.store.TpaRequests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Asking several people at once: {@code /tpahere @a} makes one request per person, all from one asker. */
class TpaRequestsEveryoneTest {

    private static final UUID OP = UUID.nameUUIDFromBytes("op".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());
    private static final UUID CAROL = UUID.nameUUIDFromBytes("carol".getBytes());
    private static final UUID DAN = UUID.nameUUIDFromBytes("dan".getBytes());

    private final AtomicLong now = new AtomicLong(1_000);
    private TpaRequests requests;

    @BeforeEach
    void setUp() {
        requests = new TpaRequests(now::get);
        requests.standingFor(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("one request per person, each answerable on its own")
    void oneEach() {
        TpaRequests.Batch batch = requests.askAll(OP, List.of(BOB, CAROL, DAN), TpaKind.HERE);

        assertThat(batch.made()).hasSize(3);
        assertThat(batch.displaced()).isEmpty();
        assertThat(requests.to(BOB)).hasSize(1);
        assertThat(requests.take(BOB, OP)).isPresent();
        assertThat(requests.take(CAROL, OP)).isPresent();
        assertThat(requests.to(DAN)).hasSize(1);
        assertThat(requests.has(OP, DAN)).isTrue();
        assertThat(requests.has(OP, BOB)).isFalse();
    }

    @Test
    @DisplayName("the asker is never one of their own targets")
    void notYourself() {
        TpaRequests.Batch batch = requests.askAll(OP, List.of(OP, BOB), TpaKind.HERE);

        assertThat(batch.made()).extracting(TpaRequest::to).containsExactly(BOB);
    }

    @Test
    @DisplayName("asking the same people again makes nothing new")
    void againIsNotAskingTwice() {
        requests.askAll(OP, List.of(BOB, CAROL), TpaKind.HERE);

        TpaRequests.Batch again = requests.askAll(OP, List.of(BOB, CAROL, DAN), TpaKind.HERE);

        assertThat(again.made()).extracting(TpaRequest::to).containsExactly(DAN);
        assertThat(requests.allFrom(OP)).hasSize(3);
    }

    @Test
    @DisplayName("asking one person afterwards is a change of mind: all the others are pushed aside")
    void oneAskDisplacesTheBatch() {
        requests.askAll(OP, List.of(BOB, CAROL), TpaKind.HERE);

        TpaRequests.Outcome outcome = requests.ask(OP, DAN, TpaKind.TO);

        assertThat(outcome.request()).isPresent();
        assertThat(outcome.displaced()).isPresent();
        assertThat(requests.allFrom(OP)).extracting(TpaRequest::to).containsExactly(DAN);
        assertThat(requests.to(BOB)).isEmpty();
    }

    @Test
    @DisplayName("a batch pushes aside an earlier single request to somebody outside it")
    void batchDisplacesSingle() {
        requests.put(OP, DAN, TpaKind.TO);

        TpaRequests.Batch batch = requests.askAll(OP, List.of(BOB, CAROL), TpaKind.HERE);

        assertThat(batch.displaced()).extracting(TpaRequest::to).containsExactly(DAN);
        assertThat(requests.to(DAN)).isEmpty();
    }

    @Test
    @DisplayName("taking one back leaves the rest; taking all back clears them")
    void withdrawing() {
        requests.askAll(OP, List.of(BOB, CAROL, DAN), TpaKind.HERE);

        assertThat(requests.withdraw(OP, BOB)).isPresent();
        assertThat(requests.allFrom(OP)).hasSize(2);
        assertThat(requests.withdrawAll(OP)).hasSize(2);
        assertThat(requests.count()).isZero();
    }

    @Test
    @DisplayName("somebody leaving ends only their own; the asker leaving ends all of them")
    void leaving() {
        requests.askAll(OP, List.of(BOB, CAROL), TpaKind.HERE);

        assertThat(requests.forget(BOB)).hasSize(1);
        assertThat(requests.allFrom(OP)).hasSize(1);
        assertThat(requests.forget(OP)).hasSize(1);
        assertThat(requests.count()).isZero();
    }

    @Test
    @DisplayName("all of them run out together, and are reported")
    void expiring() {
        requests.askAll(OP, List.of(BOB, CAROL), TpaKind.HERE);

        now.addAndGet(Duration.ofSeconds(60).toMillis());

        assertThat(requests.to(BOB)).isEmpty();
        assertThat(requests.expire()).hasSize(2);
    }
}
