package de.raindancer.modules.oneblock.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.oneblock.OneBlockServices;
import de.raindancer.modules.oneblock.model.Hold;
import de.raindancer.modules.oneblock.model.Island;
import de.raindancer.modules.oneblock.model.IslandDimension;
import de.raindancer.modules.oneblock.model.Reply;
import de.raindancer.modules.oneblock.service.IslandServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

/**
 * One island, for staff: keep it up or down for an hour, a day or for good (left click an hour, shift a day, right
 * click for good), let it go, switch its auto-unload, go there.
 */
public final class AdminIslandMenu extends Menu implements IOneBlockScreen {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd.MM. HH:mm")
            .withZone(ZoneId.of("Europe/Berlin"));

    private final OneBlockServices services;
    private final IslandServer islands;
    private final UUID islandId;

    public AdminIslandMenu(OneBlockServices services, IslandServer islands, Player viewer, Menu parent, UUID islandId) {
        super(viewer, islands.brand(), parent);
        this.services = services;
        this.islands = islands;
        this.islandId = islandId;
    }

    @Override
    protected Component title() {
        return Component.text("Island · admin", NamedTextColor.DARK_GRAY);
    }

    @Override
    public String breadcrumb() {
        return "Admin";
    }

    /** Adds the line saying what holds the island, if anything, to a button's lore. */
    static void addHoldLine(java.util.List<String> lore, Island island, long now) {
        Optional<Hold> hold = island.hold().filter(held -> held.activeAt(now));
        if (hold.isEmpty()) {
            lore.add("<gray>Held: <white>no");
            return;
        }
        String kind = hold.get().kind() == Hold.Kind.UP ? "<green>up" : "<red>down";
        String until = hold.get().forever() ? "for good" : "until " + WHEN.format(Instant.ofEpochMilli(hold.get().until()));
        lore.add("<gray>Held " + kind + " <gray>" + until + " <dark_gray>(" + hold.get().by() + ")");
    }

    @Override
    protected void render() {
        Optional<Island> found = islands.book().get(islandId);
        if (found.isEmpty()) {
            band(MenuLayout.WHO, 4, Icons.of(Material.BARRIER, "<red>That island is gone",
                    "<gray>It was given up or archived."), click -> backToWhoeverOpenedThis());
            return;
        }
        Island island = found.get();
        long now = System.currentTimeMillis();
        boolean loaded = islands.worlds().anyLoaded(island);
        java.util.List<String> lore = new java.util.ArrayList<>(java.util.List.of(
                "<dark_gray>" + island.id(),
                loaded ? "<green>Loaded <gray>· " + islands.worlds().playersOn(island).size() + " on it" : "<gray>Unloaded",
                "<gray>Money: " + services.messages().raw(island.mode().badgeKey()),
                "<gray>Shards: <light_purple>" + island.shards() + " <gray>· Broken: <white>" + island.totalBroken()));
        addHoldLine(lore, island, now);
        lore.add(island.autoUnload() ? "<gray>Auto-unload: <green>on" : "<gray>Auto-unload: <red>off");
        set(MenuLayout.HEADER_SUBJECT, Icons.of(loaded ? Material.GRASS_BLOCK : Material.DIRT,
                "<white>" + island.ownerName() + "'s island", lore));

        band(MenuLayout.WHO, 2, Icons.of(Material.LIME_CONCRETE, "<green>Keep it up",
                "<gray>Loaded now and never unloaded", "<gray>while the hold lasts.", "",
                "<yellow>Left click: <white>one hour", "<yellow>Shift click: <white>one day",
                "<yellow>Right click: <white>for good"), click -> hold(Hold.Kind.UP, click));
        band(MenuLayout.WHO, 4, island.hold().isPresent(), Icons.of(Material.WHITE_CONCRETE, "<white>Let it go",
                "<gray>Back to the usual: loaded while", "<gray>somebody plays, unloaded after.", "",
                "<yellow>Click to release the hold."), "Nothing holds this island.",
                click -> act(islands.admin().release(islandId)));
        band(MenuLayout.WHO, 6, Icons.of(Material.RED_CONCRETE, "<red>Keep it down",
                "<gray>Unloaded now; nobody but staff", "<gray>gets on while the hold lasts.", "",
                "<yellow>Left click: <white>one hour", "<yellow>Shift click: <white>one day",
                "<yellow>Right click: <white>for good"), click -> hold(Hold.Kind.DOWN, click));

        band(MenuLayout.RULES, 2, Icons.of(island.autoUnload() ? Material.CLOCK : Material.REDSTONE_BLOCK,
                        "<white>Auto-unload: " + (island.autoUnload() ? "<green>on" : "<red>off"),
                        island.autoUnload() ? "<gray>Unloaded once nobody is on it." : "<gray>Stays loaded, also after a restart.",
                        "", "<yellow>Click to switch."),
                click -> act(islands.admin().autoUnload(islandId, !island.autoUnload())));
        band(MenuLayout.RULES, 4, Icons.of(Material.ENDER_PEARL, "<white>Go there",
                "<gray>To its overworld.", "", "<yellow>Click to go."), click -> {
            viewer.closeInventory();
            islands.travel().go(viewer, island, IslandDimension.OVERWORLD);
        });
        band(MenuLayout.RULES, 6, Icons.of(Material.AMETHYST_SHARD, "<light_purple>Shards: " + island.shards(),
                        "", "<yellow>Left click: <white>+10", "<yellow>Shift click: <white>+100",
                        "<yellow>Right click: <white>-10"),
                click -> act(islands.admin().shards(islandId, click.isRightClick() ? -10 : click.isShiftClick() ? 100 : 10)));
    }

    private void hold(Hold.Kind kind, InventoryClickEvent click) {
        Optional<Duration> length = click.isRightClick() ? Optional.empty()
                : Optional.of(click.isShiftClick() ? Duration.ofDays(1) : Duration.ofHours(1));
        act(islands.admin().hold(islandId, kind, length, viewer.getName()));
    }

    private void act(Reply reply) {
        IOneBlockScreen.tell(services.messages(), viewer, reply);
        refresh();
    }

    @Override
    public String describe() {
        return "one island, for staff";
    }
}
