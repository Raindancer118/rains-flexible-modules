package de.raindancer.modules.ranks.model;

import java.util.List;

/**
 * One rank from ranks.yml.
 *
 * @param tag    the owner's own markup, drawn in front of the name — {@code <red><b>[OWNER]</b></red>}
 * @param weight higher is more senior: shown first when somebody holds several, and higher in the tablist
 */
public record Rank(String id, String title, String tag, int weight, List<String> description) {

    public Rank {
        description = description == null ? List.of() : List.copyOf(description);
    }
}
