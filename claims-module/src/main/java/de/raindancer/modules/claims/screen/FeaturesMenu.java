package de.raindancer.modules.claims.screen;

import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimFeature;
import de.raindancer.modules.claims.model.FeaturePolicy;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimFeature;
import de.raindancer.modules.claims.ClaimServices;
import de.raindancer.modules.claims.model.FeaturePolicy;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * What this claim is allowed to do at all — the server's decisions, shown to the owner.
 *
 * <p>Read-only for an owner, and that is the point of it existing. The question it answers is "why is there no
 * pantry button", and the honest answer is "the server switched it off" rather than a menu that is simply missing
 * something with no explanation. An admin sees the same screen and can change it.
 */
public final class FeaturesMenu extends PaginatedMenu<ClaimFeature> implements IClaimScreen {

    private final ClaimServices services;
    private final Claim claim;

    public FeaturesMenu(ClaimServices services, Player viewer, Claim claim, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.claim = claim;
    }

    @Override
    protected Component title() {
        return Component.text("What this claim can do");
    }

    @Override
    protected List<ClaimFeature> entries() {
        return new ArrayList<>(List.of(ClaimFeature.values()));
    }

    @Override
    protected ItemStack icon(ClaimFeature feature) {
        FeaturePolicy policy = services.features().policy(feature);
        boolean running = claim != null && services.features().isEnabled(claim, feature);
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + feature.description());
        lore.add("");
        lore.add(switch (policy) {
            case AVAILABLE -> "<green>Offered <dark_gray>· yours to switch on";
            case FORCED_ON -> "<gold>Always on <dark_gray>· the server insists";
            case FORCED_OFF -> "<red>Switched off <dark_gray>· by the server";
        });
        if (claim != null && policy.allowed()) {
            lore.add(running ? "<green>running here" : "<dark_gray>not running here");
        }
        if (services.rights().isServerAdmin(viewer)) {
            lore.add("");
            lore.add("<dark_gray>click to change (server-wide)");
        }
        return Icons.of(feature.icon(),
                (policy.allowed() ? "<white>" : "<dark_gray>") + feature.displayName(), lore);
    }

    @Override
    protected void onClick(ClaimFeature feature, InventoryClickEvent event) {
        if (!services.rights().isServerAdmin(viewer)) {
            // Said out loud rather than ignored. The lore explains the *state*; this explains the *click*,
            // and without it an owner presses the button repeatedly wondering what is broken.
            services.messages().send(viewer, "claim.feature-is-the-servers",
                    "feature", feature.displayName());
            return;
        }
        FeaturePolicy next = services.features().policy(feature).next(feature.ownerSwitchable());
        services.features().policy(feature, next);
        services.messages().send(viewer, "admin.feature-changed",
                "feature", feature.displayName(), "policy", next.displayName());
        refresh();
    }
}
