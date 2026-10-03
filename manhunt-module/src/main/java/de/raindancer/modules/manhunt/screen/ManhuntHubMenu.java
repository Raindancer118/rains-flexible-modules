package de.raindancer.modules.manhunt.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.manhunt.ManhuntServices;
import de.raindancer.modules.manhunt.hud.Announcer;
import de.raindancer.modules.manhunt.hud.HuntTicker;
import de.raindancer.modules.manhunt.model.Hunt;
import de.raindancer.modules.manhunt.setup.Goals;
import de.raindancer.modules.manhunt.setup.Preflight;
import de.raindancer.modules.manhunt.tracker.TrailPreference;
import de.raindancer.modules.manhunt.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * {@code /manhunt} and the speedrun compass' Manhunt button: the one page everything is reached from.
 * What is on it, and for whom, is {@link HubModel}'s — this only draws it and follows the clicks.
 */
public final class ManhuntHubMenu extends Menu {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Pages pages;

    public ManhuntHubMenu(Pages pages, Player viewer, Menu parent) {
        super(viewer, pages.services().brand(), parent);
        this.pages = pages;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Manhunt");
    }

    @Override
    public String breadcrumb() {
        return "Manhunt";
    }

    @Override
    protected void render() {
        ManhuntServices live = pages.services();
        Hunt hunt = live.mode().current().orElse(null);
        boolean admin = viewer.hasPermission(PermissionNodes.ADMIN);
        List<Preflight.Check> checks = admin && hunt == null ? live.desk().preflight() : List.of();
        UUID me = viewer.getUniqueId();
        HubModel.Side side = (hunt != null ? hunt.isRunner(me) : live.teams().isRunner(me)) ? HubModel.Side.RUNNER
                : (hunt != null ? hunt.isHunter(me) : live.teams().isHunter(me)) ? HubModel.Side.HUNTER
                : HubModel.Side.NONE;
        String goal = live.desk().goal();
        HubModel.View view = new HubModel.View(admin, hunt != null, live.config().sideSwitchingMidHunt(),
                live.config().runnerSelfJoin(), side,
                hunt != null ? hunt.livingRunners().size() : live.teams().runners().size(),
                hunt != null ? hunt.hunters().size() : live.teams().hunters().size(),
                (int) checks.stream().filter(check -> check.severity() == Preflight.Severity.BLOCKER).count(),
                (int) checks.stream().filter(check -> check.severity() == Preflight.Severity.WARNING).count(),
                goal.isEmpty() ? "none" : Goals.byKey(goal).map(Goals.Goal::label).orElse(goal),
                TrailPreference.shows(viewer, live.config()), HuntTicker.SIDEBAR.isOn(viewer),
                Announcer.SWITCH.isOn(viewer));
        cell(0, 4, status(hunt), null);
        for (HubModel.Button button : HubModel.buttons(view)) {
            ItemStack icon = Icons.of(material(button.icon()), button.name(), button.lore());
            if (button.band() == HubModel.TOOLBAR) {
                toolbar(button.column(), button.lockedBecause() == null, icon, button.lockedBecause(),
                        click -> follow(button));
            } else {
                band(button.band(), button.column(), button.lockedBecause() == null, icon, button.lockedBecause(),
                        click -> follow(button));
            }
        }
    }

    private ItemStack status(Hunt hunt) {
        ManhuntServices live = pages.services();
        if (hunt == null) {
            return Icons.of(Material.TARGET, "<gold>Manhunt — waiting",
                    "<gray>" + live.teams().runners().size() + " on the Runner side.",
                    "<gray>Everybody else in the lobby hunts.");
        }
        return Icons.of(Material.CLOCK, "<gold>Manhunt — the hunt is on",
                "<gray>" + hunt.livingRunners().size() + " of " + hunt.runners().size() + " Runner(s) still running.",
                "<gray>" + hunt.hunters().size() + " hunting.");
    }

    private void follow(HubModel.Button button) {
        if (button.settings()) {
            pages.settings(viewer, this, button.target());
        } else if (button.page() != null) {
            pages.open(viewer, button.page(), this);
        } else if (button.target() != null) {
            if (button.id().equals("start") || button.id().equals("here")) {
                viewer.closeInventory();
            }
            pages.run(viewer, button.target());
            if (!button.id().equals("start") && !button.id().equals("here")) {
                refresh();
            }
        }
    }

    static Material material(String name) {
        Material material = Material.matchMaterial(name);
        return material == null ? Material.PAPER : material;
    }
}
