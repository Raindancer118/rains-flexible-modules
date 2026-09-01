package de.raindancer.modules.xpbottle.screen;

import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.xpbottle.XpBottleServices;
import de.raindancer.modules.xpbottle.XpBottleSettings;
import de.raindancer.modules.xpbottle.service.BottleForge;
import de.raindancer.modules.xpbottle.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * What {@code /xpbottle} opens: what the viewer is carrying, what a bottle would hold, and every
 * siphon tier this server has.
 *
 * <h2>Why the tiers are greyed rather than hidden for an ordinary player</h2>
 * A siphon bottle is not a secret — a player who sees one in somebody else's hand should be able to
 * find out what it is and how far it reaches without asking staff. Hiding the row would make the
 * page a different shape per viewer, so nobody could be told "the second one along", and it would
 * teach players that the feature does not exist rather than that they cannot conjure one.
 */
public final class XpBottleRootMenu extends Menu implements IXpBottleScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** The most tiers one band has room for; past that the page would run into its own frame. */
    private static final int TIERS_ON_A_ROW = 7;

    private final XpBottleServices services;

    public XpBottleRootMenu(XpBottleServices services, Player viewer) {
        super(viewer, services.brand(), null);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>XP bottles");
    }

    @Override
    public String breadcrumb() {
        return "XP bottles";
    }

    @Override
    protected void render() {
        XpBottleSettings live = services.config();
        int carrying = services.bottling().experienceOf(viewer);

        set(MenuLayout.HEADER_LEFT, Icons.of(Material.EXPERIENCE_BOTTLE,
                "<green>You are carrying",
                "<white>" + carrying + "</white><gray> points",
                "<dark_gray>Level " + viewer.getLevel()));

        set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.GLASS_BOTTLE,
                "<white>A plain glass bottle",
                live.plainBottlesWork()
                        ? "<gray>Holds up to <white>" + live.capacityFor(0) + "</white> points"
                        : "<red>Switched off on this server",
                "<dark_gray>Right click one in the air to fill it."));

        set(MenuLayout.HEADER_RIGHT, Icons.of(Material.CLOCK,
                "<white>Between bottlings",
                live.fillCooldownSeconds() <= 0
                        ? "<gray>No wait"
                        : "<gray>" + live.fillCooldownSeconds() + " second(s)",
                "<dark_gray>A siphon is not covered by this."));

        boolean mayBeGiven = viewer.hasPermission(PermissionNodes.GIVE);
        int tiers = Math.min(live.highestTierClamped(), TIERS_ON_A_ROW);
        for (int tier = 1; tier <= tiers; tier++) {
            int level = tier;
            band(MenuLayout.RULES, tier, mayBeGiven, tierIcon(live, level),
                    "Only staff can conjure one of these — craft or earn it instead.",
                    click -> give(level));
        }
    }

    private ItemStack tierIcon(XpBottleSettings live, int tier) {
        return Icons.of(Material.POTION,
                "<green>Siphon Bottle " + BottleForge.numeral(tier),
                List.of(
                        "<gray>Holds <white>" + live.capacityFor(tier) + "</white> points",
                        "<gray>Reaches <white>" + live.reachFor(tier) + "</white> blocks",
                        "",
                        "<dark_gray>Hold right click to draw loose experience in.",
                        "<dark_gray>Sneak and right click to pour it back out."));
    }

    private void give(int tier) {
        if (!viewer.hasPermission(PermissionNodes.GIVE)) {
            services.messages().send(viewer, "xpbottle.give.not-allowed");
            services.effects().play(viewer.getUniqueId(), Cues.NO);
            return;
        }
        ItemStack bottle = services.forge().siphon(tier);
        viewer.getInventory().addItem(bottle).values()
                .forEach(left -> viewer.getWorld().dropItemNaturally(viewer.getLocation(), left));
        services.effects().play(viewer.getUniqueId(), Cues.REWARD);
        services.messages().send(viewer, "xpbottle.give.given",
                "tier", BottleForge.numeral(tier), "player", viewer.getName());
        refresh();
    }

    @Override
    protected List<String> helpLines() {
        return List.of(
                "Experience, put in a bottle.",
                "",
                "A plain glass bottle, right clicked in the air, draws what you are",
                "carrying into a bottle o' enchanting holding exactly that many points.",
                "Right click that to pour it back.",
                "",
                "A siphon bottle is held down instead. It pulls loose experience orbs",
                "off the ground around you, and your own when there are none nearby.",
                "Sneak and right click to pour one back out.",
                "",
                "Nothing is rounded away: a bottle gives back the number of points",
                "that went into it, whatever level you were at either time.");
    }

    @Override
    public String describe() {
        return "what a bottle would hold, and every siphon tier this server has";
    }
}
