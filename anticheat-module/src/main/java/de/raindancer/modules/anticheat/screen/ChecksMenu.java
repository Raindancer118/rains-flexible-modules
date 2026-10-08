package de.raindancer.modules.anticheat.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.anticheat.AntiCheatServices;
import de.raindancer.modules.anticheat.AntiCheatSettings;
import de.raindancer.modules.anticheat.model.CheckType;
import de.raindancer.modules.anticheat.rules.CheckStateRule;
import de.raindancer.modules.anticheat.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Every check, and a click to move it between on, watch only and off. */
public final class ChecksMenu extends PaginatedMenu<CheckType> implements IAntiCheatScreen {

    private final AntiCheatServices services;
    private final CheckStateRule states = new CheckStateRule();

    public ChecksMenu(AntiCheatServices services, Player viewer, Menu parent) {
        super(viewer, services.chat().brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Anti-cheat — <white>checks</white>");
    }

    @Override
    public String breadcrumb() {
        return "Checks";
    }

    @Override
    protected List<CheckType> entries() {
        return Arrays.asList(CheckType.values());
    }

    private CheckStateRule.Lists lists() {
        AntiCheatSettings now = services.config();
        return new CheckStateRule.Lists(now.disabledChecks(), now.silentChecks());
    }

    @Override
    protected ItemStack icon(CheckType check) {
        CheckStateRule.State state = states.state(check, lists());
        boolean experimentalOff = check.experimental() && !services.config().experimentalChecks();
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + check.category().title() + (check.experimental() ? " · experimental, alerts only" : ""));
        lore.add("<dark_gray>" + check.description());
        lore.add("");
        lore.add(experimentalOff ? "<dark_gray>Off with every experimental check."
                : state == CheckStateRule.State.ON ? "<green>On"
                : state == CheckStateRule.State.WATCH ? "<yellow>Watching only — alerts, nothing more" : "<red>Off");
        lore.add(check.kickAt() > 0 ? "<dark_gray>Kicks at level " + check.kickAt() : "<dark_gray>Never kicks");
        ItemStack icon = Icons.of(state == CheckStateRule.State.OFF || experimentalOff ? Material.GRAY_DYE : check.category().icon(),
                (state == CheckStateRule.State.ON ? "<green>" : state == CheckStateRule.State.WATCH ? "<yellow>" : "<gray>") + check.title(),
                withClickHint(lore));
        return viewer.hasPermission(PermissionNodes.MANAGE) ? icon : Icons.locked(icon, "Needs rainsanticheat.manage");
    }

    private static List<String> withClickHint(List<String> lore) {
        List<String> all = new ArrayList<>(lore);
        all.add("");
        all.add("<dark_gray>Click: on → watch only → off.");
        return all;
    }

    @Override
    protected void onClick(CheckType check, InventoryClickEvent event) {
        if (!viewer.hasPermission(PermissionNodes.MANAGE)) {
            services.messages().send(viewer, "anticheat.no-permission");
            return;
        }
        CheckStateRule.Lists next = states.next(check, lists());
        services.store().set("disabled-checks", String.join(", ", next.disabled()));
        services.store().set("silent-checks", String.join(", ", next.silent()));
        CheckStateRule.State now = states.state(check, next);
        services.messages().send(viewer, now == CheckStateRule.State.ON ? "anticheat.check-now-on"
                : now == CheckStateRule.State.WATCH ? "anticheat.check-now-watched" : "anticheat.check-now-off", "check", check.title());
        refresh();
    }

    @Override
    protected List<String> helpLines() {
        return List.of("<gray>Watch only: still alerts and keeps evidence, never sets back,",
                "<gray>cancels, kicks or bans. Good for trying a check on a new server.");
    }

    @Override
    public String describe() {
        return "every check, and whether it is on";
    }
}
