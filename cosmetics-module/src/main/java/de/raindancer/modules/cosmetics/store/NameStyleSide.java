package de.raindancer.modules.cosmetics.store;

import de.raindancer.core.data.loadout.SideData;
import de.raindancer.core.ui.identity.Identities;
import de.raindancer.core.ui.text.NameStyle;
import org.bukkit.entity.Player;

/**
 * The name style as a part of a player's side: Core keeps it with their identity, not in their persistent data, so
 * it is captured and put back here — an admin can wear a different name style as admin than in survival.
 */
public final class NameStyleSide implements SideData.Part {

    private final Identities identities;

    public NameStyleSide(Identities identities) {
        this.identities = identities;
    }

    @Override
    public String id() {
        return "cosmetics:name-style";
    }

    @Override
    public String capture(Player player) {
        return identities.hasNameStyle(player.getUniqueId()) ? identities.nameStyle(player.getUniqueId()).encode() : "";
    }

    @Override
    public void apply(Player player, String value) {
        identities.setNameStyle(player.getUniqueId(),
                value == null || value.isBlank() ? NameStyle.NONE : NameStyle.parse(value));
    }
}
