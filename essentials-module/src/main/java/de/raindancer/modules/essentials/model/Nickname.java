package de.raindancer.modules.essentials.model;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * What somebody asked to be called, kept apart from the check that decides whether they may.
 *
 * <h2>Why the plain text is kept alongside the markup</h2>
 * A nickname is read twice for two different reasons: rendered, for chat and the player list, and
 * counted, for the length limit a server actually means. Colour costs nothing towards that limit —
 * {@code <red>Tom</red>} is three characters long, not seventeen — so the plain text is computed once
 * here rather than recomputed everywhere a rule needs to know how long this actually is.
 */
public record Nickname(String raw, String plain) {

    public Nickname {
        raw = raw == null ? "" : raw.trim();
        plain = plain == null ? "" : plain;
    }

    /** Parsed once, so the caller need not know MiniMessage exists. */
    public static Nickname of(String typed) {
        String trimmed = typed == null ? "" : typed.trim();
        if (trimmed.isEmpty()) {
            return new Nickname("", "");
        }
        Component parsed = MiniMessage.miniMessage().deserialize(trimmed);
        return new Nickname(trimmed, PlainTextComponentSerializer.plainText().serialize(parsed));
    }

    public boolean isBlank() {
        return plain.isBlank();
    }

    public int length() {
        return plain.length();
    }

    /** The markup, ready to render — never re-parsed by whoever asked for it. */
    public Component rendered() {
        return MiniMessage.miniMessage().deserialize(raw);
    }
}
