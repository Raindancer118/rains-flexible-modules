package de.raindancer.modules.invsnap;

import de.raindancer.core.social.desk.DeskOffer;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.modules.invsnap.screen.InsuranceMenu;
import de.raindancer.modules.invsnap.util.PermissionNodes;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Death and item insurance at an insurance agent: the Insurance screen, opened there. */
public final class InsuranceDeskOffer implements DeskOffer {

    private final Supplier<InvSnapServices> services;

    public InsuranceDeskOffer(Supplier<InvSnapServices> services) {
        this.services = services;
    }

    @Override
    public boolean available() {
        InvSnapServices live = services.get();
        return live != null && (live.insurance().enabled() || live.itemInsurance().enabled());
    }

    @Override
    public ItemStack icon(Player viewer) {
        InvSnapServices live = services.get();
        if (!viewer.hasPermission(PermissionNodes.INSURE)) {
            return null;
        }
        List<String> lore = new ArrayList<>(summary(live, viewer));
        lore.add("");
        lore.add("<yellow>Click: open");
        return Icons.of(Material.SHIELD, "<white>Death and item insurance", lore);
    }

    /** Where the player stands: death insurance, insured items, what waits for them. */
    static List<String> summary(InvSnapServices live, Player viewer) {
        int insured = live.itemInsurance().policiesOf(viewer.getUniqueId()).size();
        int waiting = live.itemInsurance().pendingCount(viewer.getUniqueId());
        return List.of(
                "<gray>Death insurance: " + (live.insurance().isInsured(viewer.getUniqueId()) ? "<green>on" : "<red>off"),
                "<gray>Insured items: <white>" + insured,
                waiting > 0 ? "<yellow>" + waiting + " waiting to collect" : "<gray>Nothing to collect");
    }

    @Override
    public void use(Player viewer, Runnable backToDesk) {
        if (viewer.hasPermission(PermissionNodes.INSURE)) {
            new InsuranceMenu(services.get(), viewer, null).open();
        }
    }
}
