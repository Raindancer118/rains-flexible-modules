package de.raindancer.modules.roles.service;

import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.text.Markup;
import de.raindancer.modules.roles.RolesSettings;
import de.raindancer.modules.roles.model.Role;
import org.bukkit.entity.Player;

/** The player-facing side of {@link RoleShop}: does the purchase, says what came of it, and takes the role. */
public final class RolePurchase implements IRolesService {

    private final RoleShop shop;
    private final RoleService roles;
    private final Messages messages;

    public RolePurchase(RoleShop shop, RoleService roles, Messages messages) {
        this.shop = shop;
        this.roles = roles;
        this.messages = messages;
    }

    @Override
    public void settings(RolesSettings settings) {
        // Nothing here reads the settings.
    }

    public static String buyPrice(Role role) {
        return Fees.format(Fees.quote(RoleShop.BUY, role.buyPrice()));
    }

    public static String rentPrice(Role role) {
        return Fees.format(Fees.quote(RoleShop.RENT, role.rentPrice()));
    }

    public boolean buy(Player player, Role role) {
        return say(player, role, shop.buy(player.getUniqueId(), role), "roles.bought", buyPrice(role));
    }

    public boolean rent(Player player, Role role) {
        return say(player, role, shop.rent(player.getUniqueId(), role), "roles.rented", rentPrice(role));
    }

    public void cancel(Player player, Role role) {
        messages.send(player, shop.cancel(player.getUniqueId(), role) ? "roles.rent-cancelled" : "roles.rent-none",
                "role", new Markup(role.coloured()));
    }

    private boolean say(Player player, Role role, RoleShop.Result result, String doneKey, String price) {
        Markup coloured = new Markup(role.coloured());
        switch (result.outcome()) {
            case DONE -> {
                messages.send(player, doneKey, "role", coloured, "amount", Fees.format(result.charged()));
                roles.choose(player, role);
                return true;
            }
            case NOT_FOR_SALE -> messages.send(player, "roles.not-for-sale", "role", coloured);
            case SWITCHED_OFF -> messages.send(player, "roles.sales-off");
            case ALREADY_YOURS -> messages.send(player, "roles.already-yours", "role", coloured);
            case CANNOT_AFFORD -> messages.send(player, "roles.cannot-afford", "role", coloured, "amount", price);
            case NO_ECONOMY -> messages.send(player, "roles.no-economy", "role", coloured, "amount", price);
            case NOT_SAVED -> messages.send(player, "roles.not-saved");
            case REFUSED -> messages.send(player, "roles.not-charged", "role", coloured);
        }
        return false;
    }

    public String describe() {
        return "telling a player what buying or renting a role came to";
    }
}
