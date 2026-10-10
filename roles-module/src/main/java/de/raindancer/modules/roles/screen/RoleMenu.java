package de.raindancer.modules.roles.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.data.settings.SettingsMenu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.roles.RolesServices;
import de.raindancer.modules.roles.model.ChangeVerdict;
import de.raindancer.modules.roles.model.Ability;
import de.raindancer.modules.roles.model.Perk;
import de.raindancer.modules.roles.model.Role;
import de.raindancer.modules.roles.model.Ownership;
import de.raindancer.modules.roles.service.RolePurchase;
import de.raindancer.core.ui.text.Markup;
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

    private static final String SETTINGS_PERMISSION = "rainscore.settings";

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
            lore.add("<gray>Pick one below. Each pays less in the");
            lore.add("<gray>shop for different things, and does");
            lore.add("<gray>something a little better in the game.");
        } else if (left.isZero()) {
            lore.add("<green>You can change it now.");
        } else {
            lore.add("<gray>You can change it in <white>" + Times.describe(left));
        }
        if (mine.isPresent()) {
            Duration full = services.roles().untilFull(viewer.getUniqueId());
            lore.add(full.isZero() ? "<gold>Your perks are at full strength."
                    : "<gray>Perks at <white>" + Math.round(services.roles().strength(viewer.getUniqueId()) * 100)
                    + "%</white>, full in <white>" + Times.describe(full));
        }
        if (!services.roles().holdsFor().isZero()) {
            lore.add("<dark_gray>A role holds for " + Times.describe(services.roles().holdsFor()) + ".");
        }
        toolbar(4, Icons.of(mine.map(role -> icon(role.icon())).orElse(Material.NAME_TAG),
                mine.map(role -> "<white>You are " + role.article() + " " + role.coloured()).orElse("<white>You have no role yet"), lore),
                click -> { });
        if (viewer.hasPermission(SETTINGS_PERMISSION)) {
            toolbar(7, Icons.of(Material.COMPARATOR, "<white>Server settings for roles",
                            "<gray>Change-wait, perk growth and the switch",
                            "<gray>that lets players buy and rent roles.",
                            "", "<gray>Only people with the settings permission see this."),
                    click -> new SettingsMenu(viewer, services.brand(), services.core().chatFor(services.brand()),
                            services.core().settingsNavigation(), "roles", this).open());
        }
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
        boolean mine = services.roles().roleOf(viewer.getUniqueId()).filter(role::equals).isPresent();
        for (Perk perk : role.perks()) {
            int now = mine ? services.roles().perkNow(viewer.getUniqueId(), perk.percent())
                    : services.roles().perkFresh(perk.percent());
            String grows = now == perk.percent() ? "" : " <dark_gray>→ " + Math.abs(perk.percent()) + "%";
            lore.add("<green>✔ <white>" + MiniMessage.miniMessage().escapeTags(perk.says(now)) + grows);
        }
        for (Ability ability : role.abilities()) {
            int now = mine ? services.roles().abilityNow(viewer.getUniqueId(), ability)
                    : services.roles().perkFresh(ability.percent());
            String grows = now == ability.percent() ? "" : " <dark_gray>→ " + ability.percent() + "%";
            lore.add("<aqua>✦ <white>" + MiniMessage.miniMessage().escapeTags(ability.says(now)) + grows);
        }
        if (!mine && services.roles().fullAfterDays() > 0 && role.perks().stream()
                .anyMatch(perk -> services.roles().perkFresh(perk.percent()) != perk.percent())) {
            lore.add("<dark_gray>Perks start small and grow to full");
            lore.add("<dark_gray>over " + services.roles().fullAfterDays() + " days of keeping the role.");
        }
        if (!services.settings().get().perks()) {
            lore.add("<dark_gray>Perks are switched off on this server.");
        }
        if (!role.abilities().isEmpty() && !services.settings().get().abilities()) {
            lore.add("<dark_gray>Abilities are switched off on this server.");
        }
        boolean open = services.roles().may(viewer.getUniqueId(), role);
        priceLines(role, open, lore);
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
        if (!open && !services.shop().selling()) {
            return Icons.locked(item, "Roles are not for sale on this server right now.");
        }
        if (!open) {
            List<Component> forSale = new ArrayList<>(Optional.ofNullable(item.lore()).orElse(List.of()));
            forSale.add(Component.empty());
            if (role.buyable() && role.rentable()) {
                forSale.add(Icons.loreLine("<yellow>Click<gray> to buy it, <yellow>right-click<gray> to rent it"));
            } else {
                forSale.add(Icons.loreLine("<yellow>Click<gray> to " + (role.buyable() ? "buy" : "rent") + " it"));
            }
            item.lore(forSale);
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

    private void priceLines(Role role, boolean open, List<String> lore) {
        if (!role.forSale()) {
            return;
        }
        lore.add("");
        if (role.buyable()) {
            lore.add("<gold>Price: <white>" + RolePurchase.buyPrice(role) + " <dark_gray>(once, yours for good)");
        }
        if (role.rentable()) {
            lore.add("<gold>Rent: <white>" + RolePurchase.rentPrice(role) + " <dark_gray>a month, a role you cannot pay for lapses");
        }
        services.shop().ownership(viewer.getUniqueId(), role).ifPresent(have -> lore.add(
                have.kind() == Ownership.Kind.BOUGHT ? "<green>You bought this."
                        : "<green>You rent this. <gray>Next rent in <white>"
                        + Times.describe(Duration.ofMillis(Math.max(0, have.dueAt() - System.currentTimeMillis())))));
        if (!open) {
            lore.add("<red>Not yours yet.");
        }
    }

    /** The question before money is spent; the purchase itself is {@link RolePurchase}'s. */
    public void confirmPurchase(Role role, boolean rent) {
        boolean payable = rent ? role.rentable() : role.buyable();
        if (!payable) {
            services.messages().send(viewer, "roles.not-for-sale", "role", new Markup(role.coloured()));
            return;
        }
        if (!services.shop().selling()) {
            services.messages().send(viewer, "roles.sales-off");
            return;
        }
        String price = rent ? RolePurchase.rentPrice(role) + " a month" : RolePurchase.buyPrice(role);
        List<String> consequences = List.of("<gray>It costs <white>" + price + "<gray>.",
                rent ? "<gray>Rent is taken every 30 days; if it cannot be paid you lose the role."
                        : "<gray>It is yours for good.");
        new ConfirmScreen(viewer, services.brand(), this, "<dark_gray>" + (rent ? "Rent " : "Buy ") + role.coloured() + "<dark_gray>?",
                consequences, "<dark_gray>Nothing is charged if you say no.", () -> {
            if (rent) {
                services.purchases().rent(viewer, role);
            } else {
                services.purchases().buy(viewer, role);
            }
            open();
        }).open();
    }

    @Override
    protected void onClick(Role role, InventoryClickEvent event) {
        if (!services.roles().may(viewer.getUniqueId(), role)) {
            boolean rent = role.rentable() && (!role.buyable() || event.isRightClick());
            confirmPurchase(role, rent);
            return;
        }
        ChangeVerdict verdict = services.roles().verdict(viewer.getUniqueId(), role);
        if (!verdict.allowed()) {
            // Says why: it is the same role, or how long is left.
            services.roles().choose(viewer, role);
            return;
        }
        List<String> consequences = new ArrayList<>();
        role.perks().forEach(perk -> consequences.add("<gray>" + MiniMessage.miniMessage().escapeTags(
                perk.says(services.roles().perkFresh(perk.percent()))) + " <dark_gray>→ " + Math.abs(perk.percent()) + "%"));
        role.abilities().forEach(ability -> consequences.add("<aqua>" + MiniMessage.miniMessage().escapeTags(
                ability.says(services.roles().perkFresh(ability.percent()))) + " <dark_gray>→ " + ability.percent() + "%"));
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
