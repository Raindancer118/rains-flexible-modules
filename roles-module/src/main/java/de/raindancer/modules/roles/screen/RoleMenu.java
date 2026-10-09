package de.raindancer.modules.roles.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.roles.RolesServices;
import de.raindancer.modules.roles.model.ChangeVerdict;
import de.raindancer.modules.roles.model.Perk;
import de.raindancer.modules.roles.model.Role;
import de.raindancer.modules.roles.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** {@code /role}: every role with what it gets in the shop. Click one to take it. */
public final class RoleMenu extends PaginatedMenu<Role> implements IRolesScreen {

    private final RolesServices services;

    public RoleMenu(RolesServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Roles");
    }

    @Override
    public String breadcrumb() {
        return "Roles";
    }

    @Override
    protected List<Role> entries() {
        return services.roles().roles();
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>No roles on this server", "<dark_gray>Written in roles.yml");
    }

    @Override
    protected void render() {
        super.render();
        Optional<Role> mine = services.roles().roleOf(viewer.getUniqueId());
        Duration left = services.roles().left(viewer.getUniqueId());
        List<String> lore = new ArrayList<>();
        if (mine.isEmpty()) {
            lore.add("<gray>Pick one below. Each pays less");
            lore.add("<gray>in the shop for different things.");
        } else if (left.isZero()) {
            lore.add("<green>You can change it now.");
        } else {
            lore.add("<gray>You can change it in <white>" + Times.describe(left));
        }
        if (!services.roles().holdsFor().isZero()) {
            lore.add("<dark_gray>A role holds for " + Times.describe(services.roles().holdsFor()) + ".");
        }
        toolbar(4, Icons.of(mine.map(role -> icon(role.icon())).orElse(Material.NAME_TAG),
                mine.map(role -> "<white>You are " + role.article() + " " + role.coloured()).orElse("<white>You have no role yet"), lore),
                click -> { });
        if (viewer.hasPermission(PermissionNodes.BYPASS)) {
            boolean on = services.roles().bypassing(viewer.getUniqueId());
            toolbar(6, Icons.of(on ? Material.LIME_DYE : Material.GRAY_DYE,
                    on ? "<green>Bypass is on" : "<gray>Bypass is off",
                    "<gray>Staff: change role without waiting,", "<gray>to try one out.",
                    "<yellow>Click<gray> to switch it"), click -> {
                services.roles().toggleBypass(viewer.getUniqueId());
                refresh();
            });
        }
    }

    @Override
    protected ItemStack icon(Role role) {
        List<String> lore = new ArrayList<>();
        role.description().forEach(line -> lore.add("<gray>" + MiniMessage.miniMessage().escapeTags(line)));
        lore.add("");
        for (Perk perk : role.perks()) {
            lore.add((perk.percent() < 0 || perk.side() == de.raindancer.core.social.economy.TradeSide.SELL
                    ? "<green>✔ " : "<red>✘ ") + "<white>" + MiniMessage.miniMessage().escapeTags(perk.says()));
        }
        if (!services.settings().get().perks()) {
            lore.add("<dark_gray>Perks are switched off on this server.");
        }
        ChangeVerdict verdict = services.roles().verdict(viewer.getUniqueId(), role);
        ItemStack item = Icons.of(icon(role.icon()), "<white>" + role.coloured(), lore);
        if (verdict.reason() == ChangeVerdict.Reason.SAME) {
            item.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
            List<Component> withMine = new ArrayList<>(Optional.ofNullable(item.lore()).orElse(List.of()));
            withMine.add(Component.empty());
            withMine.add(Icons.loreLine("<gold>Your role"));
            item.lore(withMine);
            return item;
        }
        if (!verdict.allowed()) {
            return Icons.locked(item, "You can change again in " + Times.describe(verdict.left()) + ".");
        }
        List<Component> clickable = new ArrayList<>(Optional.ofNullable(item.lore()).orElse(List.of()));
        clickable.add(Component.empty());
        clickable.add(Icons.loreLine("<yellow>Click<gray> to become " + role.article() + " " + role.coloured()));
        item.lore(clickable);
        return item;
    }

    @Override
    protected void onClick(Role role, InventoryClickEvent event) {
        ChangeVerdict verdict = services.roles().verdict(viewer.getUniqueId(), role);
        if (!verdict.allowed()) {
            // Says why: it is the same role, or how long is left.
            services.roles().choose(viewer, role);
            return;
        }
        List<String> consequences = new ArrayList<>();
        role.perks().forEach(perk -> consequences.add("<gray>" + MiniMessage.miniMessage().escapeTags(perk.says())));
        String closing = verdict.reason() == ChangeVerdict.Reason.BYPASS || services.roles().holdsFor().isZero()
                ? "<dark_gray>You can change again whenever you like."
                : "<dark_gray>You can change again in " + Times.describe(services.roles().holdsFor()) + ".";
        new ConfirmScreen(viewer, services.brand(), this, "<dark_gray>Become " + role.article() + " " + role.coloured() + "<dark_gray>?",
                consequences, closing, () -> {
            services.roles().choose(viewer, role);
            open();
        }).open();
    }

    private static Material icon(String name) {
        Material material = Material.matchMaterial(name);
        return material == null || !material.isItem() ? Material.NAME_TAG : material;
    }

    @Override
    protected List<String> helpLines() {
        return List.of("A role makes some things cheaper in the shop,",
                "or pays more when you sell them.",
                "Pick one; change it again after the wait.");
    }

    @Override
    public String describe() {
        return "the roles, and which one is yours";
    }
}
