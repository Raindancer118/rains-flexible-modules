package de.raindancer.modules.arena.model;

import java.util.Locale;
import java.util.Optional;

/** The gladiators standing in the hub, and what each of them is for. */
public enum NpcRole {
    LANISTA("Lanista", "Fights against other players"),
    BEASTMASTER("Beast Master", "Waves of mobs"),
    ARMORER("Armorer", "The kits"),
    HERALD("Herald", "Who is the best"),
    GATEKEEPER("Gatekeeper", "Back to survival"),
    WATCHER("Watcher", "Watch the fights going on");

    private final String title;
    private final String purpose;

    NpcRole(String title, String purpose) {
        this.title = title;
        this.purpose = purpose;
    }

    public String title() {
        return title;
    }

    public String purpose() {
        return purpose;
    }

    public static Optional<NpcRole> parse(String typed) {
        if (typed == null) {
            return Optional.empty();
        }
        String name = typed.trim().toUpperCase(Locale.ROOT).replace(" ", "").replace("_", "");
        for (NpcRole role : values()) {
            if (role.name().equals(name) || role.title().toUpperCase(Locale.ROOT).replace(" ", "").equals(name)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }
}
