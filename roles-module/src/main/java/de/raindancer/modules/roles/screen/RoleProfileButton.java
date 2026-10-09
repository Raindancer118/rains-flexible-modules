package de.raindancer.modules.roles.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.profile.ProfileButton;
import de.raindancer.core.ui.profile.ProfileExtension;
import de.raindancer.modules.roles.RolesServices;
import de.raindancer.modules.roles.model.Perk;
import de.raindancer.modules.roles.model.Role;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** A player's role on their profile ({@code /player}): what they are, and what it gets them now. */
public final class RoleProfileButton implements ProfileExtension, IRolesScreen {

    private final Supplier<RolesServices> services;

    public RoleProfileButton(Supplier<RolesServices> services) {
        this.services = services;
    }

    @Override
    public ProfileButton contribute(Player viewer, OfflinePlayer subject, Menu parent) {
        RolesServices live = services.get();
        if (live == null || live.roles().roles().isEmpty()) {
            return null;
        }
        boolean self = viewer.getUniqueId().equals(subject.getUniqueId());
        Optional<Role> role = live.roles().roleOf(subject.getUniqueId());
        List<String> lore = new ArrayList<>();
        if (role.isEmpty()) {
            lore.add("<gray>No role yet.");
        } else {
            role.get().description().forEach(line -> lore.add("<gray>" + MiniMessage.miniMessage().escapeTags(line)));
            for (Perk perk : role.get().perks()) {
                lore.add("<green>✔ <white>" + MiniMessage.miniMessage().escapeTags(
                        perk.says(live.roles().perkNow(subject.getUniqueId(), perk.percent()))));
            }
        }
        if (self) {
            lore.add("");
            lore.add("<yellow>Click<gray> to see the roles");
        }
        Material icon = role.map(each -> Material.matchMaterial(each.icon())).filter(Material::isItem)
                .orElse(Material.NAME_TAG);
        String title = role.map(each -> "<white>Role: " + each.coloured()).orElse("<white>Role: <gray>none");
        return new ProfileButton(Icons.of(icon, title, lore), click -> {
            if (self) {
                new RoleMenu(live, viewer, parent).open();
            }
        });
    }

    @Override
    public String describe() {
        return "a player's role, on their profile";
    }
}
