package de.raindancer.modules.homes.screen;

import de.raindancer.core.ui.menu.ConfirmMenu;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.homes.HomeServices;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * "Buy a home slot for 50?" — Core's confirmation, with the price and what the slot is.
 *
 * <p>Opened from {@code /sethome} when the player is full, and from the homes menu. From the command the
 * home they were setting is set once the slot is bought.
 */
public final class SlotOfferScreen extends ConfirmMenu implements IHomeScreen {

    public SlotOfferScreen(HomeServices services, Player viewer, Menu parent, boolean thenSet,
                           String homeName) {
        super(viewer, services.brand(), parent, "<dark_gray>Buy a home slot?",
                List.of("<gray>One more home, for good, for <white>"
                                + services.slots().describeNextPrice(viewer.getUniqueId()) + "<gray>.",
                        "<gray>You may have " + services.keeping().describeLimitFor(viewer) + " now."
                                + (thenSet ? " The home is then set where you stand." : "")),
                "<dark_gray>The price is taken when you click Yes.",
                () -> {
                    if (services.slots().buy(viewer) && thenSet) {
                        services.keeping().set(viewer, homeName);
                    }
                });
    }

    @Override
    public String describe() {
        return "offering to sell a home slot";
    }
}
