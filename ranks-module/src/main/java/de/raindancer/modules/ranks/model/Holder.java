package de.raindancer.modules.ranks.model;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * What one player holds and how they want it shown.
 *
 * @param ranks the rank ids given to them
 * @param worn  which of them they show; empty is "the most senior", {@link #HIDDEN} is none at all
 * @param chat  their own switch for chat; the server's comes first
 */
public record Holder(UUID player, Set<String> ranks, String worn, boolean chat, boolean nametag, boolean tablist) {

    public static final String HIDDEN = "none";

    public Holder {
        ranks = Set.copyOf(new LinkedHashSet<>(ranks == null ? Set.of() : ranks));
        worn = worn == null ? "" : worn;
    }

    public static Holder fresh(UUID player) {
        return new Holder(player, Set.of(), "", true, true, true);
    }

    public Holder withRanks(Set<String> changed) {
        return new Holder(player, changed, worn, chat, nametag, tablist);
    }

    public Holder wearing(String id) {
        return new Holder(player, ranks, id, chat, nametag, tablist);
    }

    public Holder showing(Place place, boolean on) {
        return switch (place) {
            case CHAT -> new Holder(player, ranks, worn, on, nametag, tablist);
            case NAMETAG -> new Holder(player, ranks, worn, chat, on, tablist);
            case TABLIST -> new Holder(player, ranks, worn, chat, nametag, on);
        };
    }

    public boolean shows(Place place) {
        return switch (place) {
            case CHAT -> chat;
            case NAMETAG -> nametag;
            case TABLIST -> tablist;
        };
    }

    /** Nothing worth a line in the file: no ranks, and every choice still at its default. */
    public boolean isBlank() {
        return ranks.isEmpty() && worn.isEmpty() && chat && nametag && tablist;
    }
}
