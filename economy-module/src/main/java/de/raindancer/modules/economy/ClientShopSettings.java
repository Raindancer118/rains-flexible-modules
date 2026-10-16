package de.raindancer.modules.economy;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.Material;

/**
 * On a server that is not the network's bank: whether the bank's shop is shown here too. Off by default — a lobby or
 * an arena with a shop in it is a decision, not something a jar update makes for an owner.
 */
@Settings(id = "economy-client", topics = {
        @Topic(path = "client", title = "Shop on this server", icon = Material.EMERALD),
})
public record ClientShopSettings(

        @In("client") @Title("The bank's shop here")
        @Describe("/shop and /sell on this server, with the bank's prices and the network's money: items and spawn "
                + "eggs are bought and sold here, priced and booked by the bank. Books, potions, packs and the rest "
                + "stay on the bank's own server.")
        @Key("shop-here")
        boolean shopHere) {

    public static final ClientShopSettings DEFAULTS = new ClientShopSettings(false);
}
