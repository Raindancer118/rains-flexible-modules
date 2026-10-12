package de.raindancer.modules.ranks.model;

import de.raindancer.core.ui.identity.NameBadges;

import java.util.Locale;
import java.util.Optional;

/** Where a rank can be shown. */
public enum Place {
    CHAT("chat", "Chat"),
    NAMETAG("nametag", "Above your head"),
    TABLIST("tablist", "Tablist");

    private final String word;
    private final String title;

    Place(String word, String title) {
        this.word = word;
        this.title = title;
    }

    public String word() {
        return word;
    }

    public String title() {
        return title;
    }

    public static Optional<Place> of(NameBadges.Place place) {
        return switch (place) {
            case CHAT -> Optional.of(CHAT);
            case NAMETAG -> Optional.of(NAMETAG);
            case TABLIST -> Optional.of(TABLIST);
        };
    }

    public static Optional<Place> byWord(String word) {
        if (word == null) {
            return Optional.empty();
        }
        String wanted = word.toLowerCase(Locale.ROOT);
        for (Place place : values()) {
            if (place.word.equals(wanted) || (place == NAMETAG && wanted.equals("head"))
                    || (place == TABLIST && wanted.equals("tab"))) {
                return Optional.of(place);
            }
        }
        return Optional.empty();
    }
}
