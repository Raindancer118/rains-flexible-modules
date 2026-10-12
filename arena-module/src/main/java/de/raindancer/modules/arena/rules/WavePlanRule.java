package de.raindancer.modules.arena.rules;

import de.raindancer.modules.arena.api.WaveSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * What a wave is made of: how many creatures — the first wave's size, plus the growth for each wave after, capped so an
 * endless run stays a fight and not a lag spike — and which kinds, as the spec's selection says. Seeded, so a test can
 * say exactly what wave three holds.
 */
public final class WavePlanRule implements IArenaRule {

    /** The most creatures one wave ever has, endless or not. */
    public static final int MOST_PER_WAVE = 40;

    public int size(WaveSpec spec, int wave, int players) {
        int base = spec.size() + spec.growth() * Math.max(0, wave - 1);
        // More fighters, more mobs: half a wave again for each player beyond the first.
        int scaled = base + (base * Math.max(0, players - 1)) / 2;
        return Math.max(1, Math.min(MOST_PER_WAVE, scaled));
    }

    public List<String> creatures(WaveSpec spec, int wave, int players, long seed) {
        int count = size(spec, wave, players);
        List<String> mobs = spec.mobs();
        List<String> creatures = new ArrayList<>(count);
        Random random = new Random(seed * 31 + wave);
        for (int index = 0; index < count; index++) {
            creatures.add(switch (spec.selection()) {
                case ONE -> mobs.getFirst();
                case CYCLE -> mobs.get((wave - 1) % mobs.size());
                case RANDOM -> mobs.get(random.nextInt(mobs.size()));
            });
        }
        return creatures;
    }

    @Override
    public String describe() {
        return "how big each wave is and what it is made of";
    }
}
