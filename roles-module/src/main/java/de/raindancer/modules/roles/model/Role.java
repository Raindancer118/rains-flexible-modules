package de.raindancer.modules.roles.model;

import java.util.List;

/**
 * A role a player can take: who they are on the server, and what the shop gives them for it.
 *
 * @param colour a hex colour for the role's name, {@code #e8a33d}
 */
public record Role(String id, String title, String icon, String colour, List<String> description, List<Perk> perks) {

    public Role {
        description = List.copyOf(description);
        perks = List.copyOf(perks);
    }

    /** "a" or "an", for the role's name — an Explorer, a Cook. */
    public String article() {
        return !title.isEmpty() && "AEIOUaeiou".indexOf(title.charAt(0)) >= 0 ? "an" : "a";
    }

    /** The name in its colour, as MiniMessage. */
    public String coloured() {
        return "<" + colour + ">" + net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(title)
                + "</" + colour + ">";
    }
}
