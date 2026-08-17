package de.raindancer.modules.tpa.profile;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.profile.ProfileButton;
import de.raindancer.core.ui.profile.ProfileExtension;
import de.raindancer.modules.tpa.TpaServices;
import de.raindancer.modules.tpa.model.TpaKind;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * "Request a teleport" on somebody's profile — {@code /tpa} in one click rather than a name typed
 * correctly. No confirmation dialog of its own: {@code TpaRequestService.ask} already says whatever
 * needs saying — a cooldown, a refusal, the request itself — the same as typing the command would.
 *
 * <h2>Why only when they are online</h2>
 * There is nowhere to teleport to if they are not here, the same reason {@code AskCommand} never
 * offers this to anybody {@code getPlayerExact} does not find.
 */
public final class RequestTeleportProfileExtension implements ProfileExtension {

    private final TpaServices services;

    public RequestTeleportProfileExtension(TpaServices services) {
        this.services = services;
    }

    @Override
    public ProfileButton contribute(Player viewer, OfflinePlayer subject, Menu parent) {
        if (subject == null || viewer.getUniqueId().equals(subject.getUniqueId())) {
            return null;
        }
        Player online = Bukkit.getPlayer(subject.getUniqueId());
        if (online == null) {
            return null;
        }
        ItemStack icon = Icons.of(Material.ENDER_PEARL, "<yellow>Request a teleport",
                "<gray>Ask to teleport to <white>" + online.getName() + "</white>.",
                "<dark_gray>The same as /tpa.");
        return new ProfileButton(icon, click -> {
            viewer.closeInventory();
            // Re-fetched rather than trusted from this closure: the button was drawn when the page
            // opened, and asking needs them online right now, not when the page was rendered.
            Player stillHere = Bukkit.getPlayer(subject.getUniqueId());
            if (stillHere == null) {
                services.messages().send(viewer, "tpa.no-such-player", "player", online.getName());
                return;
            }
            services.asking().ask(viewer, stillHere, TpaKind.TO);
        });
    }
}
