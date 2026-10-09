package de.raindancer.modules.invsnap;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.profile.ProfileButton;
import de.raindancer.core.ui.profile.ProfileExtension;
import de.raindancer.modules.invsnap.screen.InsuranceMenu;
import de.raindancer.modules.invsnap.util.PermissionNodes;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.function.Supplier;

/** "Insurance" on a player's own profile page — nothing on anybody else's. */
public final class InsuranceProfileExtension implements ProfileExtension {

    private final Supplier<InvSnapServices> services;

    public InsuranceProfileExtension(Supplier<InvSnapServices> services) {
        this.services = services;
    }

    @Override
    public ProfileButton contribute(Player viewer, OfflinePlayer subject, Menu parent) {
        InvSnapServices live = services.get();
        if (live == null || !viewer.getUniqueId().equals(subject.getUniqueId())
                || !viewer.hasPermission(PermissionNodes.INSURE)
                || !(live.insurance().enabled() || live.itemInsurance().enabled()
                || live.itemInsurance().pendingCount(viewer.getUniqueId()) > 0)) {
            return null;
        }
        int insured = live.itemInsurance().policiesOf(viewer.getUniqueId()).size();
        int waiting = live.itemInsurance().pendingCount(viewer.getUniqueId());
        return new ProfileButton(Icons.of(Material.SHIELD, "<aqua>Insurance",
                "<gray>Death insurance: " + (live.insurance().isInsured(viewer.getUniqueId()) ? "<green>on" : "<red>off"),
                "<gray>Insured items: <white>" + insured,
                waiting > 0 ? "<yellow>" + waiting + " waiting to collect" : "<gray>Nothing to collect",
                "", "<yellow>Click: open"),
                click -> new InsuranceMenu(live, viewer, parent).open());
    }
}
