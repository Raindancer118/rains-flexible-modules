package de.raindancer.modules.essentials.model;

import java.util.List;

/** A whole set of rules under a name, put in place in one go with {@code /rules preset apply}. */
public record RulePreset(String name, String description, List<HouseRule> rules) {

    public RulePreset {
        description = description == null ? "" : description.strip();
        rules = rules == null ? List.of() : List.copyOf(rules);
    }
}
