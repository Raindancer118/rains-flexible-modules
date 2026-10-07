package de.raindancer.modules.voicebridge.service;

import de.raindancer.modules.voicebridge.VoiceBridgeSettings;
import de.raindancer.modules.voicebridge.model.Placement;
import de.raindancer.modules.voicebridge.util.Pcm;
import de.raindancer.modules.voicebridge.util.Spatial;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Everything one player would hear, as one stereo stream for their Discord ear: each voice kept apart
 * in its own jitter buffer, then faded and panned from where that player stands and looks.
 */
public final class PersonalMix implements IVoiceBridgeService {

    private final SpeakerMixer<UUID> sources;
    private final Map<UUID, Placement> placements = new ConcurrentHashMap<>();
    private final Function<UUID, Optional<Spatial.Ear>> positions;

    public PersonalMix(int prebuffer, int most, long idleMillis, LongSupplier clock,
                       Function<UUID, Optional<Spatial.Ear>> positions) {
        this.sources = new SpeakerMixer<>(prebuffer, most, idleMillis, clock);
        this.positions = positions;
    }

    @Override
    public void settings(VoiceBridgeSettings settings) {
        sources.settings(settings);
    }

    /** One decoded frame from one voice channel, and where it sounds from right now. */
    public void offer(UUID channel, short[] frame, Placement placement) {
        placements.put(channel, placement);
        sources.offer(channel, frame);
    }

    public boolean hasAudio() {
        return sources.hasAudio();
    }

    /** 20 ms of 48 kHz stereo big-endian, or {@code null} when there is nothing to hear. */
    public byte[] next(Spatial.Ear ear) {
        Map<UUID, short[]> frames = sources.nextFrames();
        if (frames.isEmpty()) {
            return null;
        }
        int length = Pcm.FRAME_SAMPLES;
        for (short[] frame : frames.values()) {
            length = Math.max(length, frame.length);
        }
        int[] stereo = new int[length * 2];
        for (Map.Entry<UUID, short[]> heard : frames.entrySet()) {
            Placement placement = placements.getOrDefault(heard.getKey(), new Placement.Static());
            float[] gains = gains(ear, placement);
            Spatial.addInto(stereo, heard.getValue(), gains[0], gains[1]);
        }
        return Spatial.toBigEndian(stereo);
    }

    private float[] gains(Spatial.Ear ear, Placement placement) {
        return switch (placement) {
            case Placement.Static ignored -> new float[]{1f, 1f};
            case Placement.At at -> placed(ear, at.x(), at.y(), at.z(), at.distance());
            case Placement.Following following -> positions.apply(following.entity())
                    .map(where -> placed(ear, where.x(), where.y(), where.z(), following.distance()))
                    // SVC only sends an entity's voice while it is in range, so an entity we cannot
                    // place is near; centred is closer to the truth than silence.
                    .orElse(new float[]{1f, 1f});
        };
    }

    private static float[] placed(Spatial.Ear ear, double x, double y, double z, double distance) {
        float fade = Spatial.distanceGain(Spatial.distance(ear, x, y, z), distance);
        float[] ears = Spatial.ears(ear, x, y, z);
        return new float[]{ears[0] * fade, ears[1] * fade};
    }

    public void forget(UUID channel) {
        placements.remove(channel);
        sources.forget(channel);
    }

    public void clear() {
        placements.clear();
        sources.clear();
    }
}
