package de.raindancer.modules.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Putting back what a module took, in the opposite order it took it.
 *
 * <p>Reverse order is not tidiness. A module that opened a database and then started a timer reading
 * from it has to stop the timer first; closing the database first leaves a tick reading a closed
 * connection, and the exception lands on the server's scheduler thread rather than anywhere near the
 * shutdown that caused it.
 *
 * <p>And every resource is released even when an earlier one throws — the one thing this class exists
 * to guarantee, because a plain try-with-resources chain stops at the first failure.
 */
class UnwindTest {

    private final List<String> closed = new ArrayList<>();

    private AutoCloseable named(String name) {
        return () -> closed.add(name);
    }

    private AutoCloseable failing(String name) {
        return () -> {
            closed.add(name);
            throw new IllegalStateException("could not close " + name);
        };
    }

    @Test
    void closesNothingWhenGivenNothing() {
        assertThat(new Unwind().close()).isEmpty();
    }

    @Test
    void closesInReverseOrder() {
        Unwind unwind = new Unwind();
        unwind.add(named("database"));
        unwind.add(named("timer"));

        assertThat(unwind.close()).isEmpty();
        assertThat(closed).containsExactly("timer", "database");
    }

    @Test
    void closesEverythingEvenWhenOneThrows() {
        Unwind unwind = new Unwind();
        unwind.add(named("first"));
        unwind.add(failing("second"));
        unwind.add(named("third"));

        List<Throwable> trouble = unwind.close();

        assertThat(closed).containsExactly("third", "second", "first");
        assertThat(trouble).hasSize(1);
        assertThat(trouble.getFirst()).hasMessageContaining("second");
    }

    @Test
    void reportsEveryFailureRatherThanTheFirst() {
        Unwind unwind = new Unwind();
        unwind.add(failing("a"));
        unwind.add(failing("b"));

        assertThat(unwind.close()).hasSize(2);
    }

    @Test
    void containsAnErrorAsWellAsAnException() {
        Unwind unwind = new Unwind();
        unwind.add(() -> {
            throw new StackOverflowError("deep");
        });
        unwind.add(named("after"));

        assertThat(unwind.close()).hasSize(1);
        assertThat(closed).containsExactly("after");
    }

    @Test
    void closesEachResourceOnlyOnce() {
        Unwind unwind = new Unwind();
        unwind.add(named("only"));

        unwind.close();
        unwind.close();

        assertThat(closed).containsExactly("only");
    }

    @Test
    void refusesResourcesAddedAfterItClosed() {
        Unwind unwind = new Unwind();
        unwind.close();
        // Late is not merely useless — it is a leak that reads as registered. Closing it at once is
        // the only honest answer, because there will be no second unwind to catch it.
        unwind.add(named("late"));
        assertThat(closed).containsExactly("late");
    }

    @Test
    void ignoresANullResourceRatherThanFailingLater() {
        Unwind unwind = new Unwind();
        unwind.add(null);
        unwind.add(named("real"));
        assertThat(unwind.close()).isEmpty();
        assertThat(closed).containsExactly("real");
    }

    @Test
    void countsWhatItIsHolding() {
        Unwind unwind = new Unwind();
        assertThat(unwind.size()).isZero();
        unwind.add(named("one"));
        unwind.add(named("two"));
        assertThat(unwind.size()).isEqualTo(2);
        unwind.close();
        assertThat(unwind.size()).isZero();
    }
}
