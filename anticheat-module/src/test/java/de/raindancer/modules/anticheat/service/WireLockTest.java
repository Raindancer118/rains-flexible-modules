package de.raindancer.modules.anticheat.service;

import de.raindancer.modules.anticheat.model.PlayerTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * The packet thread must never wait for the movement engine. The engine holds a player's track for
 * the whole of its tick, reading the world; anything the Netty thread does for that player has to go
 * through other locks.
 */
class WireLockTest {

    @Test
    @DisplayName("a click is counted while the engine holds the player's track")
    void clickDoesNotWaitForTheEngine() throws Exception {
        PlayerTrack track = new PlayerTrack(UUID.randomUUID(), "Busy", System::currentTimeMillis);
        ClickService clicks = new ClickService();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread engine = new Thread(() -> {
            synchronized (track) {
                held.countDown();
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        engine.start();
        held.await();
        try {
            assertTimeoutPreemptively(Duration.ofMillis(500), () -> clicks.click(track, 1000));
        } finally {
            release.countDown();
            engine.join();
        }
    }

    @Test
    @DisplayName("knockback can be confirmed while the engine holds the player's track")
    void velocitiesAreLockFree() throws Exception {
        PlayerTrack track = new PlayerTrack(UUID.randomUUID(), "Busy", System::currentTimeMillis);
        track.movement.velocities.add(new double[]{0, 0.4, 0, 0, -100, 0, 7, 0});
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread engine = new Thread(() -> {
            synchronized (track) {
                held.countDown();
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        engine.start();
        held.await();
        try {
            assertTimeoutPreemptively(Duration.ofMillis(500), () -> {
                synchronized (track.wire) {
                    for (double[] velocity : track.movement.velocities) {
                        velocity[7] = 1;
                    }
                }
            });
        } finally {
            release.countDown();
            engine.join();
        }
    }
}
