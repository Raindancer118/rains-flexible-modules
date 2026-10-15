package de.raindancer.modules.oneblock.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.oneblock.OneBlockServices;
import de.raindancer.modules.oneblock.model.Content;
import de.raindancer.modules.oneblock.model.Island;
import de.raindancer.modules.oneblock.model.IslandDimension;
import de.raindancer.modules.oneblock.model.Spawn;
import de.raindancer.modules.oneblock.service.IslandServer;
import de.raindancer.modules.oneblock.service.SidebarService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The island's page: its three dimensions (go, or what unlocking costs), the upgrades, the team, the spawn and the
 * sidebar — and giving it up, behind a confirmation.
 */
public final class IslandMenu extends Menu implements IOneBlockScreen {

    private static final int[] DIMENSION_COLUMNS = {1, 3, 5};

    private final OneBlockServices services;
    private final IslandServer islands;

    public IslandMenu(OneBlockServices services, IslandServer islands, Player viewer, Menu parent) {
        super(viewer, islands.brand(), parent);
        this.services = services;
        this.islands = islands;
    }

    @Override
    protected Component title() {
        return Component.text("Your island", NamedTextColor.DARK_GRAY);
    }

    @Override
    public String breadcrumb() {
        return "Island";
    }

    @Override
    protected void render() {
        Optional<Island> found = islands.book().ofPlayer(viewer.getUniqueId());
        if (found.isEmpty()) {
            band(MenuLayout.WHO, 4, Icons.of(Material.GRASS_BLOCK, "<green>You have no island yet",
                    "", "<yellow>Click to start one."), click -> new ChooseModeMenu(services, islands, viewer, this, true).open());
            return;
        }
        Island island = found.get();
        Content content = islands.currentContent();
        boolean owner = island.owner().equals(viewer.getUniqueId());
        set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.GRASS_BLOCK, "<green>" + island.ownerName() + "'s island",
                "<gray>Money: " + services.messages().raw(island.mode().badgeKey()),
                "<gray>Shards: <light_purple>" + String.format("%,d", island.shards()),
                "<gray>Team: <white>" + island.everyone().size() + "<gray>/" + content.teamSize(),
                "<gray>Blocks broken: <white>" + String.format("%,d", island.totalBroken())));

        IslandDimension[] dimensions = IslandDimension.values();
        for (int i = 0; i < dimensions.length; i++) {
            dimension(island, content, owner, dimensions[i], DIMENSION_COLUMNS[i]);
        }
        band(MenuLayout.WHO, 7, Icons.head(viewer.getUniqueId(), "<white>Team",
                        "<gray>" + island.everyone().size() + " of " + content.teamSize() + " places taken.",
                        "", "<gray>Invite with <white>/island invite <dark_gray>name", "<yellow>Click to see the team."),
                click -> new TeamMenu(services, islands, viewer, this).open());

        IslandDimension here = Island.dimensionOf(viewer.getWorld().getName()).orElse(IslandDimension.OVERWORLD);
        band(MenuLayout.RULES, 2, Icons.of(Material.ANVIL, "<light_purple>Upgrades",
                        "<gray>What your blocks turn into,", "<gray>paid with shards.", "",
                        "<yellow>Click to open them."),
                click -> new UpgradesMenu(services, islands, viewer, this,
                        island.unlocked(here) ? here : IslandDimension.OVERWORLD).open());
        boolean onOwn = islands.book().ofWorld(viewer.getWorld().getName())
                .filter(standing -> standing.id().equals(island.id())).isPresent();
        band(MenuLayout.RULES, 4, onOwn, Icons.of(Material.RED_BED, "<white>Set the spawn here",
                        "<gray>Where your team arrives in this", "<gray>dimension of the island.", "",
                        "<yellow>Click to set it where you stand."), "Stand on your own island to set its spawn.",
                click -> {
                    Location at = viewer.getLocation();
                    IOneBlockScreen.tell(services.messages(), viewer, islands.islands().setSpawn(viewer.getUniqueId(),
                            at.getWorld().getName(), new Spawn(at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch())));
                    refresh();
                });
        boolean shown = SidebarService.SHOWN.isOn(viewer);
        band(MenuLayout.RULES, 6, Icons.of(shown ? Material.LIME_DYE : Material.GRAY_DYE,
                        "<white>Sidebar: " + (shown ? "<green>shown" : "<gray>hidden"), "", "<yellow>Click to switch."),
                click -> {
                    viewer.performCommand("island sidebar");
                    refresh();
                });

        List<String> consequences = new ArrayList<>();
        if (owner) {
            consequences.add(island.members().isEmpty() ? "<red>You give the island up. It is kept, but nobody plays it."
                    : "<red>Not while your team is on it: kick them first.");
        } else {
            consequences.add("<gray>You leave " + island.ownerName() + "'s team.");
        }
        danger(Icons.of(Material.BARRIER, owner ? "<red>Give up the island" : "<red>Leave the island",
                        consequences.toArray(String[]::new)),
                click -> new ConfirmScreen(islands.brand(), viewer, this,
                        owner ? "Give up your island?" : "Leave the island?", consequences, () -> {
                    IOneBlockScreen.tell(services.messages(), viewer, islands.islands().leave(viewer.getUniqueId()));
                    if (islands.book().ofPlayer(viewer.getUniqueId()).isEmpty()) {
                        islands.travel().toWaiting(viewer);
                        viewer.closeInventory();
                    }
                }).open());
    }

    private void dimension(Island island, Content content, boolean owner, IslandDimension dimension, int column) {
        String name = services.messages().raw(dimension.nameKey());
        if (island.unlocked(dimension)) {
            long broken = island.block(dimension).map(block -> block.broken()).orElse(0L);
            band(MenuLayout.WHO, column, Icons.of(dimension.icon(), "<green>" + name,
                            "<gray>Blocks broken: <white>" + String.format("%,d", broken), "",
                            "<yellow>Click to go there."),
                    click -> {
                        viewer.closeInventory();
                        islands.travel().go(viewer, island, dimension);
                    });
            return;
        }
        long price = content.unlockPrice(dimension).orElse(0L);
        boolean affordable = island.shards() >= price;
        band(MenuLayout.WHO, column, owner && affordable, Icons.of(dimension.icon(), "<gray>" + name + " <dark_gray>(locked)",
                        "<gray>Unlock for <light_purple>" + String.format("%,d", price) + " shards<gray>.",
                        "<gray>The island has <light_purple>" + String.format("%,d", island.shards()) + "<gray>.", "",
                        "<yellow>Click to unlock it."),
                owner ? "The island needs " + String.format("%,d", price - island.shards()) + " more shards."
                        : "Only the owner unlocks dimensions.",
                click -> new ConfirmScreen(islands.brand(), viewer, this, "Unlock " + name + "?",
                        List.of("<gray>Costs <light_purple>" + String.format("%,d", price) + " shards<gray>.",
                                "<gray>Shards spent are not given back."),
                        () -> {
                            IOneBlockScreen.tell(services.messages(), viewer,
                                    islands.islands().unlock(viewer.getUniqueId(), dimension));
                            new IslandMenu(services, islands, viewer, parent()).open();
                        }).open());
    }

    @Override
    public String describe() {
        return "a player's island page";
    }
}
