package de.raindancer.modules.arena.api;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A {@link MatchSpec} as the plain text a link message carries, and back. Anything missing or garbled is the default. */
public final class SpecCodec {

    private SpecCodec() {
    }

    public static Map<String, String> encode(MatchSpec spec) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("mode", spec.mode().name());
        data.put("kit", spec.kit());
        data.put("bestOf", String.valueOf(spec.bestOf()));
        data.put("stake", String.valueOf(spec.stake()));
        data.put("teamSize", String.valueOf(spec.teamSize()));
        if (spec.waves() != null) {
            WaveSpec waves = spec.waves();
            data.put("preset", waves.preset());
            data.put("waves", String.valueOf(waves.waves()));
            data.put("selection", waves.selection().name());
            data.put("mobs", String.join(",", waves.mobs()));
            data.put("size", String.valueOf(waves.size()));
            data.put("growth", String.valueOf(waves.growth()));
        }
        return data;
    }

    public static MatchSpec decode(Map<String, String> data) {
        Mode mode = Mode.parse(data.get("mode")).orElse(Mode.DUEL);
        WaveSpec waves = null;
        if (mode == Mode.WAVES) {
            WaveSpec preset = WaveSpec.preset(data.get("preset")).orElse(null);
            if (preset != null && !data.containsKey("mobs")) {
                waves = preset;
            } else {
                WaveSpec.Selection selection;
                try {
                    selection = WaveSpec.Selection.valueOf(data.getOrDefault("selection", "RANDOM").toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException unknown) {
                    selection = WaveSpec.Selection.RANDOM;
                }
                List<String> mobs = data.containsKey("mobs") ? Arrays.asList(data.get("mobs").split(",")) : List.of();
                waves = new WaveSpec(data.getOrDefault("preset", "custom"), number(data, "waves", 5), selection, mobs,
                        number(data, "size", 3), number(data, "growth", 1));
            }
        }
        return new MatchSpec(mode, data.get("kit"), number(data, "bestOf", 1), number(data, "stake", 0L),
                number(data, "teamSize", 2), waves);
    }

    private static int number(Map<String, String> data, String key, int fallback) {
        try {
            return Integer.parseInt(data.getOrDefault(key, String.valueOf(fallback)).trim());
        } catch (NumberFormatException garbled) {
            return fallback;
        }
    }

    private static long number(Map<String, String> data, String key, long fallback) {
        try {
            return Long.parseLong(data.getOrDefault(key, String.valueOf(fallback)).trim());
        } catch (NumberFormatException garbled) {
            return fallback;
        }
    }
}
