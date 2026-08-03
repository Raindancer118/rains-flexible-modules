package de.raindancer.modules.moderation;

import de.raindancer.modules.moderation.model.Report;
import de.raindancer.modules.moderation.model.StaffNote;
import de.raindancer.modules.moderation.service.StaffChatService;
import de.raindancer.modules.moderation.store.NoteRegistry;
import de.raindancer.modules.moderation.store.ReportRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading a store while it is being written.
 *
 * <h2>Why this is not paranoia</h2>
 * A report arrives from a chat event, which Paper fires off the server thread. The screen that lists
 * them renders on the server thread. On Folia a staff chat message can be sent from any region thread
 * at all. The two concurrency tests already in this repository each reproduced a real
 * {@link java.util.ConcurrentModificationException} within a handful of rounds — a plugin-wide crash
 * from a for-loop over a plain {@code ArrayList}.
 *
 * <p>These are not proof of thread safety; nothing short of a model checker is. They are a trap that
 * catches the specific mistake — a mutable collection handed out, or iterated while something else
 * writes to it — reliably enough to have caught it twice.
 */
class ConcurrencyTest {

    private static final int ROUNDS = 400;
    private static final Instant WHEN = Instant.parse("2026-08-03T12:00:00Z");

    private static Report report(String id, UUID subject) {
        return Report.filed(id, UUID.randomUUID(), "Ayla", subject, "Bram", "something happened", WHEN);
    }

    /** Runs the two bodies against each other until one of them throws, or the rounds run out. */
    private static void raceOf(Runnable writer, Runnable reader) throws InterruptedException {
        AtomicReference<Throwable> broke = new AtomicReference<>();
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        for (Runnable body : List.of(writer, reader)) {
            pool.execute(() -> {
                try {
                    go.await();
                    for (int round = 0; round < ROUNDS && broke.get() == null; round++) {
                        body.run();
                    }
                } catch (Throwable failed) {
                    broke.compareAndSet(null, failed);
                } finally {
                    done.countDown();
                }
            });
        }
        go.countDown();
        boolean finished = done.await(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(finished).as("the race did not finish, which usually means something deadlocked").isTrue();
        if (broke.get() != null) {
            throw new AssertionError("a concurrent read of the store threw", broke.get());
        }
    }

    @Test
    @DisplayName("the report queue can be listed while reports are arriving")
    void reportsAreSafeToList() throws InterruptedException {
        ReportRegistry reports = new ReportRegistry();
        UUID subject = UUID.randomUUID();

        raceOf(() -> reports.add(report(reports.nextId(), subject)),
                () -> {
                    for (Report report : reports.all()) {
                        assertThat(report.id()).isNotBlank();
                    }
                    reports.waiting();
                    reports.about(subject);
                    reports.waitingCount();
                });
    }

    @Test
    @DisplayName("a report can be claimed while the queue is being read")
    void reportsAreSafeToChange() throws InterruptedException {
        ReportRegistry reports = new ReportRegistry();
        UUID subject = UUID.randomUUID();
        UUID staff = UUID.randomUUID();
        for (int i = 0; i < 50; i++) {
            reports.add(report(reports.nextId(), subject));
        }

        raceOf(() -> reports.all().forEach(report -> reports.add(report.claimedBy(staff, "Cyra"))),
                () -> reports.live().forEach(report -> assertThat(report.state()).isNotNull()));
    }

    @Test
    @DisplayName("notes can be read while they are being written")
    void notesAreSafe() throws InterruptedException {
        NoteRegistry notes = new NoteRegistry();
        UUID subject = UUID.randomUUID();
        UUID author = UUID.randomUUID();

        raceOf(() -> notes.add(new StaffNote(notes.nextId(), subject, author, "Cyra", "watching", WHEN)),
                () -> {
                    for (StaffNote note : notes.about(subject)) {
                        assertThat(note.text()).isNotBlank();
                    }
                    notes.subjects();
                    notes.size();
                });
    }

    @Test
    @DisplayName("two reports filed at the same moment do not get the same id")
    void idsAreReservedNotGuessed() throws InterruptedException {
        // Found by review, and it is the worst kind of loss: a report arrives from a chat event, so
        // two players reporting at once are genuinely on two threads. Both ask for the next id, both
        // are told "R1", and the second `add` overwrites the first. No error, no log line — one
        // player's report simply never existed.
        //
        // nextId() therefore has to *reserve*, not predict.
        ReportRegistry reports = new ReportRegistry();
        UUID subject = UUID.randomUUID();
        int perThread = 200;
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(4);
        ExecutorService pool = Executors.newFixedThreadPool(4);

        for (int thread = 0; thread < 4; thread++) {
            pool.execute(() -> {
                try {
                    go.await();
                    for (int i = 0; i < perThread; i++) {
                        reports.add(report(reports.nextId(), subject));
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        go.countDown();
        boolean finished = done.await(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(finished).isTrue();
        assertThat(reports.size())
                .as("every report that was filed should still be there — a duplicate id silently "
                        + "overwrites somebody's report")
                .isEqualTo(4 * perThread);
    }

    @Test
    @DisplayName("two notes written at the same moment do not get the same id")
    void noteIdsAreReservedToo() throws InterruptedException {
        NoteRegistry notes = new NoteRegistry();
        UUID subject = UUID.randomUUID();
        UUID author = UUID.randomUUID();

        AtomicReference<Throwable> broke = new AtomicReference<>();
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(3);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        for (int thread = 0; thread < 3; thread++) {
            pool.execute(() -> {
                try {
                    go.await();
                    for (int i = 0; i < 200; i++) {
                        notes.add(new StaffNote(notes.nextId(), subject, author, "Cyra", "x", WHEN));
                    }
                } catch (Throwable failed) {
                    broke.compareAndSet(null, failed);
                } finally {
                    done.countDown();
                }
            });
        }
        go.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(broke.get()).isNull();
        assertThat(notes.size()).isEqualTo(600);
    }

    @Test
    @DisplayName("staff chat can be toggled while it is being asked about")
    void staffChatIsSafe() throws InterruptedException {
        // Chat events are asynchronous, so the answer to "is this person in staff chat" is read off a
        // different thread from the one the toggle command ran on. Always.
        StaffChatService chat = new StaffChatService();
        UUID who = UUID.randomUUID();

        raceOf(() -> {
            chat.toggle(who);
            chat.forget(UUID.randomUUID());
        }, () -> {
            chat.isTalking(who);
            chat.everybodyTalking();
        });
    }
}
