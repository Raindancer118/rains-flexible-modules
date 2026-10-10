package de.raindancer.modules.roles.model;

import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.Money;

import java.util.List;

/**
 * A role a player can take: who they are on the server, what the shop gives them for it, and what they
 * can do a little better in the game.
 *
 * @param colour a hex colour for the role's name, {@code #e8a33d}
 * @param price   what buying it costs, as written in roles.yml; "0" means it cannot be bought
 * @param rent    what renting it costs per month, as written; "0" means it cannot be rented
 */
public record Role(String id, String title, String icon, String colour, List<String> description, List<Perk> perks,
                   String price, String rent, List<Ability> abilities) {

    public Role {
        description = List.copyOf(description);
        perks = List.copyOf(perks);
        abilities = abilities == null ? List.of() : List.copyOf(abilities);
        price = price == null ? "0" : price;
        rent = rent == null ? "0" : rent;
    }

    public Role(String id, String title, String icon, String colour, List<String> description, List<Perk> perks,
                String price, String rent) {
        this(id, title, icon, colour, description, perks, price, rent, List.of());
    }

    /** A role nobody has to pay for. */
    public Role(String id, String title, String icon, String colour, List<String> description, List<Perk> perks) {
        this(id, title, icon, colour, description, perks, "0", "0");
    }

    public Money buyPrice() {
        return Fees.amount(price);
    }

    public Money rentPrice() {
        return Fees.amount(rent);
    }

    public boolean buyable() {
        return buyPrice().isPositive();
    }

    public boolean rentable() {
        return rentPrice().isPositive();
    }

    /** Whether it has to be paid for before it can be taken. */
    public boolean forSale() {
        return buyable() || rentable();
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
