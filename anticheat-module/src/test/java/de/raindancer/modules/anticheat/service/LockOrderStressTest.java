package de.raindancer.modules.anticheat.service;

import de.raindancer.modules.anticheat.model.PlayerTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * The engine takes the track and, inside it, the wire (the ping probe); the packet thread takes the wire and, inside
 * it, reads exemptions. Both run hard against each other: any lock cycle is a frozen server and a watchdog restart.
 */
class LockOrderStressTest {

    @RepeatedTest(5)
    @DisplayName("engine-side and packet-side lock use never deadlock")
    void noDeadlock() throws Exception {
        PlayerTrack track = new PlayerTrack(UUID.randomUUID(), "Busy", System::currentTimeMillis);
        AtomicBoolean stop = new AtomicBoolean();
        Thread engine = new Thread(() -> {
            for (int i = 0; i < 200_000 && !stop.get(); i++) {
                synchronized (track) {
                    synchronized (track.wire) {
                        track.packets.nextTransaction++;
                    }
                    track.exempt(PlayerTrack.Exemption.ELYTRA, 50);
                    track.isExempt(PlayerTrack.Exemption.ELYTRA);
                }
            }
        }, "engine");
        Thread packets = new Thread(() -> {
            for (int i = 0; i < 200_000 && !stop.get(); i++) {
                synchronized (track.wire) {
                    track.exemption();
                    track.exemptMillisLeft(PlayerTrack.Exemption.ELYTRA);
                }
            }
        }, "packets");
        engine.start();
        packets.start();
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
                engine.join();
                packets.join();
            });
        } finally {
            stop.set(true);
            long[] stuck = ManagementFactory.getThreadMXBean().findDeadlockedThreads();
            assertThat(stuck).as("deadlocked threads").isNull();
        }
    }
}
