package de.raindancer.modules.voicebridge.service;

import de.raindancer.modules.voicebridge.VoiceBridgeSettings;
import de.raindancer.modules.voicebridge.util.Pcm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Many speakers in, one 20 ms frame out — a small jitter buffer per speaker, mixed on demand.
 *
 * <p>Packets arrive whenever the network delivers them; the consumer (Discord's send loop) asks
 * exactly every 20 ms. Each speaker is held back until {@code prebuffer} frames are queued, so the
 * first word is not chopped by the second packet arriving late, and is capped at {@code most}
 * frames by dropping the oldest, so a burst after a lag spike can never become a permanent delay.
 *
 * <p>Thread-safe: offered from the voice chat's network thread, drained from Discord's.
 */
public final class SpeakerMixer<K> implements IVoiceBridgeService {

    private static final class Speaker {
        final ArrayDeque<short[]> frames = new ArrayDeque<>();
        boolean primed;
        long lastHeard;
    }

    private final Map<K, Speaker> speakers = new LinkedHashMap<>();
    private final long idleMillis;
    private final LongSupplier clock;
    private int prebuffer;
    private int most;

    public SpeakerMixer(int prebuffer, int most, long idleMillis, LongSupplier clock) {
        this.idleMillis = idleMillis;
        this.clock = clock;
        limits(prebuffer, most);
    }

    public synchronized void limits(int prebufferFrames, int mostFrames) {
        most = Math.max(1, mostFrames);
        prebuffer = Math.max(1, Math.min(prebufferFrames, most));
        for (Speaker speaker : speakers.values()) {
            trim(speaker);
        }
    }

    @Override
    public void settings(VoiceBridgeSettings settings) {
        limits(settings.prebufferFrames(), settings.bufferFramesClamped());
    }

    public synchronized void offer(K speaker, short[] frame) {
        Speaker state = speakers.computeIfAbsent(speaker, ignored -> new Speaker());
        state.frames.addLast(frame);
        state.lastHeard = clock.getAsLong();
        trim(state);
    }

    /** The next 20 ms of everybody together, or {@code null} when nobody has anything to play. */
    public short[] next() {
        Map<K, short[]> frames = nextFrames();
        return frames.isEmpty() ? null : Pcm.mix(new ArrayList<>(frames.values()));
    }

    /** The next 20 ms of each speaker who has some, kept apart for a mix that places them. */
    public synchronized Map<K, short[]> nextFrames() {
        Map<K, short[]> playing = new LinkedHashMap<>();
        long now = clock.getAsLong();
        Iterator<Map.Entry<K, Speaker>> all = speakers.entrySet().iterator();
        while (all.hasNext()) {
            Map.Entry<K, Speaker> entry = all.next();
            Speaker speaker = entry.getValue();
            if (!speaker.primed && speaker.frames.size() >= prebuffer) {
                speaker.primed = true;
            }
            if (speaker.primed) {
                short[] frame = speaker.frames.pollFirst();
                if (frame == null) {
                    speaker.primed = false;
                } else {
                    playing.put(entry.getKey(), frame);
                }
            }
            if (speaker.frames.isEmpty() && now - speaker.lastHeard > idleMillis) {
                all.remove();
            }
        }
        return playing;
    }

    public synchronized boolean hasAudio() {
        for (Speaker speaker : speakers.values()) {
            if (speaker.primed ? !speaker.frames.isEmpty() : speaker.frames.size() >= prebuffer) {
                return true;
            }
        }
        return false;
    }

    public synchronized void forget(K speaker) {
        speakers.remove(speaker);
    }

    public synchronized void clear() {
        speakers.clear();
    }

    public synchronized int speakers() {
        return speakers.size();
    }

    public synchronized int buffered(K speaker) {
        Speaker state = speakers.get(speaker);
        return state == null ? 0 : state.frames.size();
    }

    private void trim(Speaker speaker) {
        while (speaker.frames.size() > most) {
            speaker.frames.pollFirst();
        }
    }
}
