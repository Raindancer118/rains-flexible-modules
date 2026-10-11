package de.raindancer.modules.invsnap;

import de.raindancer.core.social.desk.ServiceDesk;
import de.raindancer.core.social.desk.ServiceDesks;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.profile.ProfileButton;
import de.raindancer.core.ui.profile.ProfileExtension;
import de.raindancer.modules.invsnap.screen.InsuranceMenu;
import de.raindancer.modules.invsnap.util.PermissionNodes;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
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
        boolean atAgent = ServiceDesks.inPersonOnly(ServiceDesk.INSURANCE);
        List<String> lore = new ArrayList<>(InsuranceDeskOffer.summary(live, viewer));
        lore.add("");
        lore.add(atAgent ? "<gray>Arranged with an " + ServiceDesk.INSURANCE.title() : "<yellow>Click: open");
        return new ProfileButton(Icons.of(Material.SHIELD, "<aqua>Insurance", lore),
                click -> {
                    if (!ServiceDesks.refuseRemote(viewer, ServiceDesk.INSURANCE)) {
                        new InsuranceMenu(live, viewer, parent).open();
                    }
                });
    }
}
