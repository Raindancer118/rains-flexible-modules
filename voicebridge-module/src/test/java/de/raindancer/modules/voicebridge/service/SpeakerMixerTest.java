package de.raindancer.modules.voicebridge.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class SpeakerMixerTest {

    private final AtomicLong now = new AtomicLong(1_000);
    private SpeakerMixer<String> mixer;

    @BeforeEach
    void setUp() {
        mixer = new SpeakerMixer<>(2, 5, 5_000, now::get);
    }

    private static short[] frame(int value) {
        return new short[]{(short) value, (short) value};
    }

    @Test
    @DisplayName("nobody talking is nothing to send, not a frame of silence")
    void silenceIsNull() {
        assertThat(mixer.next()).isNull();
        assertThat(mixer.hasAudio()).isFalse();
    }

    @Test
    @DisplayName("a speaker is held back until two frames are in, so network jitter does not chop the start")
    void prebuffers() {
        mixer.offer("alex", frame(1));
        assertThat(mixer.next()).as("one frame is not enough yet").isNull();

        mixer.offer("alex", frame(2));
        assertThat(mixer.next()).containsExactly((short) 1, (short) 1);
        assertThat(mixer.next()).containsExactly((short) 2, (short) 2);
    }

    @Test
    @DisplayName("running dry primes the speaker again instead of playing a half-empty stream")
    void underrunReprimes() {
        mixer.offer("alex", frame(1));
        mixer.offer("alex", frame(2));
        mixer.next();
        mixer.next();
        assertThat(mixer.next()).isNull();

        mixer.offer("alex", frame(3));
        assertThat(mixer.next()).as("a single frame after running dry waits for a second").isNull();
        mixer.offer("alex", frame(4));
        assertThat(mixer.next()).containsExactly((short) 3, (short) 3);
    }

    @Test
    @DisplayName("two speakers in the same 20 ms are heard together")
    void mixesSpeakers() {
        mixer.offer("alex", frame(100));
        mixer.offer("alex", frame(100));
        mixer.offer("sam", frame(20));
        mixer.offer("sam", frame(20));

        assertThat(mixer.next()).containsExactly((short) 120, (short) 120);
    }

    @Test
    @DisplayName("a speaker who is not primed yet does not hold back one who is")
    void primedSpeakersDoNotWait() {
        mixer.offer("alex", frame(100));
        mixer.offer("alex", frame(100));
        mixer.offer("sam", frame(20));

        assertThat(mixer.next()).containsExactly((short) 100, (short) 100);
    }

    @Test
    @DisplayName("frames can be taken per speaker, for a mix that treats each one differently")
    void framesPerSpeaker() {
        mixer.offer("alex", frame(100));
        mixer.offer("alex", frame(100));
        mixer.offer("sam", frame(20));
        mixer.offer("sam", frame(20));

        var frames = mixer.nextFrames();

        assertThat(frames).containsOnlyKeys("alex", "sam");
        assertThat(frames.get("sam")).containsExactly((short) 20, (short) 20);
        assertThat(mixer.nextFrames()).as("one frame each per call").hasSize(2);
        assertThat(mixer.nextFrames()).isEmpty();
    }

    @Test
    @DisplayName("a backlog past the limit drops the oldest audio, so delay can never pile up")
    void capsTheDelay() {
        for (int i = 1; i <= 8; i++) {
            mixer.offer("alex", frame(i));
        }

        assertThat(mixer.buffered("alex")).isEqualTo(5);
        assertThat(mixer.next()).as("frames 1 to 3 were dropped").containsExactly((short) 4, (short) 4);
    }

    @Test
    @DisplayName("somebody who left is forgotten, and anything they still had queued goes with them")
    void forget() {
        mixer.offer("alex", frame(1));
        mixer.offer("alex", frame(1));
        mixer.forget("alex");

        assertThat(mixer.next()).isNull();
        assertThat(mixer.speakers()).isZero();
    }

    @Test
    @DisplayName("a speaker silent for longer than the idle time is dropped, so the map does not grow forever")
    void prunesIdleSpeakers() {
        mixer.offer("alex", frame(1));
        mixer.offer("alex", frame(1));
        mixer.next();
        mixer.next();
        now.addAndGet(5_001);

        mixer.next();

        assertThat(mixer.speakers()).isZero();
    }

    @Test
    @DisplayName("a speaker with audio still queued is never pruned, however old the last packet")
    void doesNotPruneQueuedAudio() {
        mixer.offer("alex", frame(1));
        now.addAndGet(60_000);

        mixer.next();

        assertThat(mixer.speakers()).isEqualTo(1);
    }

    @Test
    @DisplayName("new limits apply to what is already queued")
    void limitsCanChange() {
        for (int i = 1; i <= 5; i++) {
            mixer.offer("alex", frame(i));
        }
        mixer.limits(1, 2);
        mixer.offer("alex", frame(6));

        assertThat(mixer.buffered("alex")).isEqualTo(2);
    }

    @Test
    @DisplayName("nonsense limits are clamped rather than believed")
    void limitsAreClamped() {
        mixer.limits(0, 0);
        mixer.offer("alex", frame(1));

        assertThat(mixer.next()).as("prebuffer of at least one, buffer of at least one").isNotNull();
    }

    @Test
    @DisplayName("offered on the voice thread while Discord's thread mixes, nothing is lost or thrown")
    void concurrentOfferAndMix() throws Exception {
        SpeakerMixer<String> big = new SpeakerMixer<>(1, 100_000, 60_000, now::get);
        int frames = 20_000;
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = new ArrayList<>();
        Thread producer = Thread.ofPlatform().start(() -> {
            try {
                start.await();
                for (int i = 0; i < frames; i++) {
                    big.offer("alex", frame(1));
                }
            } catch (Throwable failure) {
                synchronized (failures) {
                    failures.add(failure);
                }
            }
        });
        AtomicLong mixed = new AtomicLong();
        Thread consumer = Thread.ofPlatform().start(() -> {
            try {
                start.await();
                while (mixed.get() < frames) {
                    if (big.next() != null) {
                        mixed.incrementAndGet();
                    }
                }
            } catch (Throwable failure) {
                synchronized (failures) {
                    failures.add(failure);
                }
            }
        });
        start.countDown();
        producer.join(10_000);
        consumer.join(10_000);

        assertThat(failures).isEmpty();
        assertThat(mixed.get()).isEqualTo(frames);
    }
}
