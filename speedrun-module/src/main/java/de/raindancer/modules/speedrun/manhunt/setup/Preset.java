package de.raindancer.modules.speedrun.manhunt.setup;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.modules.speedrun.manhunt.ManhuntSettings;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Three ready-made hunts for the setup wizard, each a set of changes from the defaults — so applying
 * one always starts from the defaults and two presets never mix.
 */
public enum Preset {

    /** The defaults: one life, a compass that follows through portals, no frills in the way. */
    CLASSIC(Map.of()),

    /** Friends on a sofa: two lives, a team compass, a long head start, glowing Runners now and then. */
    CASUAL(ordered(
            "runner-lives", "2",
            "tracker-team-compass", "true",
            "runner-structure-compass", "true",
            "hunter-head-start-seconds", "45",
            "head-start-per-hunter-seconds", "5",
            "glowing-runners-every-minutes", "10",
            "side-switching-mid-hunt", "true")),

    /** For people who practise: no head start, fists only between Hunters, a wait after dying. */
    SWEATY(ordered(
            "hunter-head-start-seconds", "0",
            "hunters-fists-only", "true",
            "hunter-respawn-delay-seconds", "5",
            "tracker-particle-trail", "false",
            "runner-offline-grace-seconds", "120"));

    private final Map<String, String> values;

    Preset(Map<String, String> values) {
        this.values = values;
    }

    /** What it changes from the defaults, setting key to value. */
    public Map<String, String> changes() {
        return values;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<Preset> byId(String id) {
        for (Preset preset : values()) {
            if (preset.id().equalsIgnoreCase(id)) {
                return Optional.of(preset);
            }
        }
        return Optional.empty();
    }

    /** The defaults, then this preset's changes. @return how many of its changes took */
    public int applyTo(SettingsStore<ManhuntSettings> store) {
        store.resetAll();
        int applied = 0;
        for (Map.Entry<String, String> change : values.entrySet()) {
            if (store.set(change.getKey(), change.getValue())) {
                applied++;
            }
        }
        return applied;
    }

    private static Map<String, String> ordered(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return Map.copyOf(map);
    }
}
