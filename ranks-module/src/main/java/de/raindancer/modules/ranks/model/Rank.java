package de.raindancer.modules.ranks.model;

import java.util.List;

/**
 * One rank from ranks.yml.
 *
 * @param tag    the owner's own markup, drawn in front of the name — {@code <red><b>[OWNER]</b></red>}
 * @param weight higher is more senior: shown first when somebody holds several, and higher in the tablist
 * @param team   whether holding it puts somebody in the server's team — listed on its own in the tablist
 */
public record Rank(String id, String title, String tag, int weight, List<String> description, boolean team) {

    public Rank {
        description = description == null ? List.of() : List.copyOf(description);
    }

    /** Not part of the team. */
    public Rank(String id, String title, String tag, int weight, List<String> description) {
        this(id, title, tag, weight, description, false);
    }

    public Rank withTag(String newTag) {
        return new Rank(id, title, newTag, weight, description, team);
    }

    public Rank withTitle(String newTitle) {
        return new Rank(id, newTitle, tag, weight, description, team);
    }

    public Rank withWeight(int newWeight) {
        return new Rank(id, title, tag, newWeight, description, team);
    }

    public Rank withTeam(boolean onTeam) {
        return new Rank(id, title, tag, weight, description, onTeam);
    }
}
